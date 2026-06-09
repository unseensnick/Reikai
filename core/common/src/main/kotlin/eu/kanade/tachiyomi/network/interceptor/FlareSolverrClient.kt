package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.AndroidCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves Cloudflare challenges via a self-hosted FlareSolverr proxy.
 *
 * Extracted from Reikai's CloudflareInterceptor port so the patch on Mihon's interceptor stays
 * a small island: this class owns all FlareSolverr internals (DTOs, the FS HTTP client, the
 * shared session, per-host dedup, cookie install, and User-Agent pinning), while
 * [CloudflareInterceptor] keeps the challenge detection and decides when to delegate here.
 */
class FlareSolverrClient(
    private val cookieManager: AndroidCookieJar,
) {

    private val flareSolverrClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    // One active FlareSolverr solve per hostname; concurrent 403s for the same host coalesce.
    private val pendingFSSolves = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    // Per-host User-Agent pin set when FlareSolverr solves a challenge. Subsequent requests to
    // that host must use this UA so the cf_clearance cookie (bound to it) keeps validating.
    private val fsPinByHost = ConcurrentHashMap<String, String>()

    // Hosts where WebView has failed and FlareSolverr has succeeded. Skip the wasted 30 s
    // WebView pre-attempt on subsequent requests within this app session.
    private val fsRequiredHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Single shared FlareSolverr session ID. Lazily created on the first FS call and reused
    // across all subsequent calls so the FS browser keeps cf_clearance / __cf_bm in-memory
    // and most follow-up requests skip the JS challenge entirely.
    private val fsSessionLock = Any()
    @Volatile private var fsSessionId: String? = null

    fun pinnedUserAgentFor(host: String): String? = fsPinByHost[host]

    fun shouldSkipWebView(host: String): Boolean = fsRequiredHosts.contains(host)

    /**
     * Solve the challenge for [request] through the FlareSolverr server at [flareSolverrUrl] and
     * return its fully-fetched response. Returns null when a sibling thread is already solving for
     * the same host (the caller should then fall through to a normal retry with the cookie jar).
     */
    fun resolve(flareSolverrUrl: String, request: Request): Response? =
        resolveWithFlareSolverrDedup(flareSolverrUrl, request)

    /**
     * Connectivity check for the settings "Test" button: a sessionless solve of google.com.
     * Returns the User-Agent FlareSolverr reports on success so the caller can pin it as the
     * app default. Runs off the main thread.
     */
    suspend fun test(flareSolverrUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val command = buildJsonObject {
                put("cmd", "request.get")
                put("url", "https://www.google.com/")
                put("maxTimeout", 60000)
            }
            val body = json.encodeToString(JsonObject.serializer(), command)
                .toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder()
                .url("${flareSolverrUrl.trimEnd('/')}/v1")
                .post(body)
                .build()
            val text = flareSolverrClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("FlareSolverr returned HTTP ${resp.code}")
                resp.body.string()
            }
            val result = json.decodeFromString(FlareSolverrResponse.serializer(), text)
            if (result.status != "ok") {
                throw IOException(result.message.ifBlank { "FlareSolverr error" })
            }
            val solution = result.solution
                ?: throw IOException("FlareSolverr returned no solution")
            if (solution.status !in 200..299) {
                throw IOException("FlareSolverr solution status: ${solution.status}")
            }
            solution.userAgent
        }
    }

    private fun resolveWithFlareSolverrDedup(flareSolverrUrl: String, request: Request): Response? {
        val host = request.url.host

        while (true) {
            val existing = pendingFSSolves[host]
            if (existing != null) {
                // Another thread is already solving for this host. Wait for it, then return null
                // so the caller falls through to chain.proceed with whatever the cookie jar has.
                existing.get(90, TimeUnit.SECONDS)
                return null
            }
            val future = CompletableFuture<Unit>()
            if (pendingFSSolves.putIfAbsent(host, future) == null) {
                try {
                    cookieManager.remove(request.url, COOKIE_NAMES, 0)
                    val response = resolveWithFlareSolverr(flareSolverrUrl, request)
                    future.complete(Unit)
                    return response
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                    throw e
                } finally {
                    pendingFSSolves.remove(host)
                }
            }
            // Another thread won the race between our check and putIfAbsent. Loop and wait.
        }
    }

    private fun resolveWithFlareSolverr(flareSolverrUrl: String, request: Request): Response {
        val sessionId = ensureFlareSolverrSession(flareSolverrUrl)
        return runFlareSolverrRequest(flareSolverrUrl, request, sessionId, allowRetry = true)
    }

    private fun ensureFlareSolverrSession(flareSolverrUrl: String): String {
        fsSessionId?.let { return it }
        return synchronized(fsSessionLock) {
            fsSessionId?.let { return@synchronized it }
            val newId = "reikai-${UUID.randomUUID()}"
            val body = """{"cmd":"sessions.create","session":"$newId"}"""
                .toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder()
                .url("${flareSolverrUrl.trimEnd('/')}/v1")
                .post(body)
                .build()
            flareSolverrClient.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    throw IOException("FlareSolverr sessions.create HTTP ${resp.code}")
                }
                val parsed = json.decodeFromString(FlareSolverrResponse.serializer(), text)
                if (parsed.status != "ok") {
                    throw IOException("FlareSolverr sessions.create error: ${parsed.message}")
                }
            }
            fsSessionId = newId
            newId
        }
    }

    private fun runFlareSolverrRequest(
        flareSolverrUrl: String,
        request: Request,
        sessionId: String,
        allowRetry: Boolean,
    ): Response {
        val targetUrl = request.url.toString()

        // Full-response mode (returnOnlyCookies defaults to false). FS returns the page body its
        // headless Chrome fetched, so we can serve it directly and avoid replaying cf_clearance
        // through OkHttp, a path Cloudflare's TLS / __cf_bm fingerprinting often rejects. The
        // session keeps cleared cookies in-memory so follow-up calls skip the JS challenge.
        //
        // A Cloudflare-gated POST must be replayed as a POST with the original body, otherwise FS
        // would GET the URL and return the wrong page. FS sends postData as
        // application/x-www-form-urlencoded, which is what these bodies already are. Build the
        // command via the JSON DSL so the body can't break the envelope.
        val isPost = request.method.equals("POST", ignoreCase = true)
        val postData = request.body
            ?.takeIf { isPost }
            ?.let { rb -> Buffer().also { rb.writeTo(it) }.readUtf8() }
        val command = buildJsonObject {
            put("cmd", if (isPost) "request.post" else "request.get")
            put("url", targetUrl)
            if (isPost) put("postData", postData ?: "")
            put("session", sessionId)
            put("maxTimeout", 60000)
        }
        val body = json.encodeToString(JsonObject.serializer(), command)
            .toRequestBody(JSON_MEDIA_TYPE)

        val fsRequest = Request.Builder()
            .url("${flareSolverrUrl.trimEnd('/')}/v1")
            .post(body)
            .build()

        val fsResponse = flareSolverrClient.newCall(fsRequest).execute()
        val fsBody = fsResponse.body.string()

        if (!fsResponse.isSuccessful) {
            throw IOException("FlareSolverr returned HTTP ${fsResponse.code}")
        }

        val result = json.decodeFromString(FlareSolverrResponse.serializer(), fsBody)

        // FS restart, container reboot, or session GC invalidates the cached ID. Detect that
        // case via the error message ("Session ${id} not found.") and recreate once.
        if (result.status != "ok" && allowRetry &&
            result.message.contains("session", ignoreCase = true)
        ) {
            synchronized(fsSessionLock) {
                if (fsSessionId == sessionId) fsSessionId = null
            }
            val freshId = ensureFlareSolverrSession(flareSolverrUrl)
            return runFlareSolverrRequest(flareSolverrUrl, request, freshId, allowRetry = false)
        }

        if (result.status != "ok") {
            throw IOException("FlareSolverr error: ${result.message}")
        }

        val solution = result.solution
            ?: throw IOException("FlareSolverr returned no solution: ${result.message}")

        if (solution.status !in 200..299) {
            throw IOException("FlareSolverr solution status: ${solution.status}")
        }

        // Best-effort: stash cookies + UA so unrelated future requests to this host can succeed
        // without re-invoking FS. The current request's correctness comes from the synthetic
        // response we build below, not from these.
        solution.cookies.forEach { fsCookie ->
            cookieManager.saveCookieString(request.url, fsCookie.toRawCookieString(request.url.host))
        }
        if (solution.userAgent.isNotBlank()) {
            fsPinByHost[request.url.host] = solution.userAgent
        }

        // Mark this host as known-FS so the next request skips the WebView pre-attempt.
        fsRequiredHosts.add(request.url.host)

        return buildResponseFromFlareSolverr(request, solution)
    }

    private fun buildResponseFromFlareSolverr(request: Request, solution: FlareSolverrSolution): Response {
        val contentType = solution.headers
            .entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?: "text/html; charset=UTF-8"

        val body = solution.response.toResponseBody(contentType.toMediaTypeOrNull())

        val headersBuilder = Headers.Builder()
        solution.headers.forEach { (name, value) ->
            // FlareSolverr returns the body already decoded, so passing through Content-Encoding
            // / Content-Length / Transfer-Encoding would make OkHttp try to re-decode and break.
            // Set-Cookie was already applied to the cookie jar above; skip it here too.
            if (name.equals("content-encoding", ignoreCase = true)) return@forEach
            if (name.equals("content-length", ignoreCase = true)) return@forEach
            if (name.equals("transfer-encoding", ignoreCase = true)) return@forEach
            if (name.equals("set-cookie", ignoreCase = true)) return@forEach
            runCatching { headersBuilder.add(name, value) }
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(solution.status)
            .message(if (solution.status in 200..299) "OK" else "FlareSolverr")
            .headers(headersBuilder.build())
            .body(body)
            .build()
    }
}

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val COOKIE_NAMES = listOf("cf_clearance")

@Serializable
private data class FlareSolverrResponse(
    val status: String,
    val message: String = "",
    // Nullable because sessions.create responses have no solution field.
    val solution: FlareSolverrSolution? = null,
)

@Serializable
private data class FlareSolverrSolution(
    val url: String = "",
    val status: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val response: String = "",
    val cookies: List<FlareSolverrCookie> = emptyList(),
    @SerialName("userAgent") val userAgent: String = "",
)

@Serializable
private data class FlareSolverrCookie(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "/",
    val expires: Double = -1.0,
    val httpOnly: Boolean = false,
    val secure: Boolean = false,
) {
    fun toRawCookieString(requestHost: String): String = buildString {
        append("$name=$value")
        // Preserve (or add) a leading dot so Android CookieManager treats this as a domain
        // cookie that matches the apex domain and all its subdomains.
        val dom = when {
            domain.isBlank() -> ".$requestHost"
            domain.startsWith('.') -> domain
            else -> ".$domain"
        }
        append("; Domain=$dom")
        append("; Path=$path")
        if (expires > 0) {
            val fmt = SimpleDateFormat("EEE, dd-MMM-yyyy HH:mm:ss z", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            append("; Expires=${fmt.format(Date((expires * 1000).toLong()))}")
        }
        if (secure) append("; Secure")
        if (httpOnly) append("; HttpOnly")
    }
}
