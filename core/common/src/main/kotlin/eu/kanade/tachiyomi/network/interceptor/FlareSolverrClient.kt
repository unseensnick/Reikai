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
import org.jsoup.Jsoup
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

    // Byparr (a Camoufox-based FlareSolverr-compatible solver) is sessionless: it has no
    // sessions.create command and 500s on it. Once we see that, stop creating sessions and send
    // sessionless request.get / request.post for the rest of this app session.
    @Volatile private var fsSessionsSupported = true

    fun pinnedUserAgentFor(host: String): String? = fsPinByHost[host]

    fun shouldSkipWebView(host: String): Boolean = fsRequiredHosts.contains(host)

    /**
     * Mark a host as WebView-unsolvable so later requests skip the WebView pre-attempt and go
     * straight to FlareSolverr. Called when the WebView solve fails: without this the 30s WebView
     * timeout is re-paid on every request to a host the WebView can't clear (browsing, details,
     * chapter and image loads), serialising 30s blocks on the main thread and hanging access to
     * that host. [fsRequiredHosts] is otherwise only populated on an FS success, which never
     * happens for a host FS also can't clear.
     */
    fun markWebViewUnsolvable(host: String) {
        fsRequiredHosts.add(host)
    }

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
        return runFlareSolverrRequest(flareSolverrUrl, request, sessionId, allowRetry = sessionId != null)
    }

    /**
     * Returns the shared FlareSolverr session id, or null when the server is sessionless (Byparr) or
     * sessions.create otherwise fails. A null result means "send requests without a session"; the
     * solve still proceeds, just without the warm-cookie reuse a FlareSolverr session would give.
     */
    private fun ensureFlareSolverrSession(flareSolverrUrl: String): String? {
        if (!fsSessionsSupported) return null
        fsSessionId?.let { return it }
        return synchronized(fsSessionLock) {
            if (!fsSessionsSupported) return@synchronized null
            fsSessionId?.let { return@synchronized it }
            // Only real FlareSolverr implements sessions. Byparr is sessionless and 500s on
            // sessions.create (it has no url to navigate), spamming its console with a stack trace.
            // Probe the root banner so we attempt sessions.create only on FlareSolverr.
            if (!flareSolverrSupportsSessions(flareSolverrUrl)) {
                fsSessionsSupported = false
                return@synchronized null
            }
            val newId = "reikai-${UUID.randomUUID()}"
            val body = """{"cmd":"sessions.create","session":"$newId"}"""
                .toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder()
                .url("${flareSolverrUrl.trimEnd('/')}/v1")
                .post(body)
                .build()
            val created = runCatching {
                flareSolverrClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching false
                    val parsed = json.decodeFromString(FlareSolverrResponse.serializer(), resp.body.string())
                    parsed.status == "ok"
                }
            }.getOrDefault(false)
            if (!created) {
                // Sessionless solver (Byparr) or a transient error: fall back to sessionless requests
                // rather than failing the solve. request.get still works without a session.
                fsSessionsSupported = false
                return@synchronized null
            }
            fsSessionId = newId
            newId
        }
    }

    /**
     * True only for real FlareSolverr, whose root endpoint returns a JSON banner containing
     * "FlareSolverr". Byparr (and other sessionless solvers) return something else, so we skip the
     * sessions.create command they can't handle. Failures default to false (go sessionless).
     */
    private fun flareSolverrSupportsSessions(flareSolverrUrl: String): Boolean = runCatching {
        val req = Request.Builder().url("${flareSolverrUrl.trimEnd('/')}/").get().build()
        flareSolverrClient.newCall(req).execute().use { resp ->
            resp.isSuccessful && resp.body.string().contains("FlareSolverr", ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun runFlareSolverrRequest(
        flareSolverrUrl: String,
        request: Request,
        sessionId: String?,
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
            if (sessionId != null) put("session", sessionId)
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
        if (result.status != "ok" && allowRetry && sessionId != null &&
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
        val reportedContentType = solution.headers
            .entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?: "text/html; charset=UTF-8"

        // A browser-based solver (Byparr/Camoufox, FlareSolverr/Chrome) renders a JSON-API response
        // inside its built-in JSON/plaintext viewer (<html>...<pre>{json}</pre>...), so a JSON source
        // (or light-novel plugin) would receive HTML and fail to parse. Unwrap it back to the raw JSON
        // the caller expects; HTML page sources are untouched (their <pre>, if any, isn't JSON).
        val unwrappedJson = unwrapBrowserJsonViewer(solution.response)
        val responseText = unwrappedJson ?: solution.response
        val contentType = if (unwrappedJson != null) JSON_CONTENT_TYPE else reportedContentType

        val body = responseText.toResponseBody(contentType.toMediaTypeOrNull())

        val headersBuilder = Headers.Builder()
        solution.headers.forEach { (name, value) ->
            // FlareSolverr returns the body already decoded, so passing through Content-Encoding
            // / Content-Length / Transfer-Encoding would make OkHttp try to re-decode and break.
            // Set-Cookie was already applied to the cookie jar above; skip it here too. Content-Type
            // is set from [contentType] below so it stays consistent with any JSON unwrap.
            if (name.equals("content-encoding", ignoreCase = true)) return@forEach
            if (name.equals("content-length", ignoreCase = true)) return@forEach
            if (name.equals("transfer-encoding", ignoreCase = true)) return@forEach
            if (name.equals("set-cookie", ignoreCase = true)) return@forEach
            if (name.equals("content-type", ignoreCase = true)) return@forEach
            runCatching { headersBuilder.add(name, value) }
        }
        headersBuilder.add("Content-Type", contentType)

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
private const val JSON_CONTENT_TYPE = "application/json; charset=UTF-8"
private val COOKIE_NAMES = listOf("cf_clearance")

/**
 * A browser-based Cloudflare solver renders a JSON-API response inside the browser's built-in
 * JSON / plaintext viewer, e.g. `<html>...<body><pre>{json}</pre>...</body></html>`
 * (Firefox / Camoufox, used by Byparr) or the same shape with a json-formatter div (Chrome, used by
 * FlareSolverr). A JSON manga source or light-novel plugin then receives HTML and fails to parse.
 *
 * If [response] is such a wrapper, return the raw JSON it holds; otherwise null (serve as-is).
 * Detection is browser-agnostic: the body is markup whose first `<pre>` (entity-decoded via Jsoup's
 * [org.jsoup.nodes.Element.wholeText], so `&lt;` in string values is restored) is itself JSON.
 */
// internal so the unwrap is unit-testable, like FlareSolverrCookie below.
internal fun unwrapBrowserJsonViewer(response: String): String? {
    if (!response.trimStart().startsWith('<')) return null
    if (!response.contains("<pre", ignoreCase = true)) return null
    val pre = Jsoup.parse(response).selectFirst("pre")?.wholeText()?.trim().orEmpty()
    return pre.takeIf { it.startsWith('{') || it.startsWith('[') }
}

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
// internal (was private) so toRawCookieString's leading-dot domain logic is unit-testable.
internal data class FlareSolverrCookie(
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
