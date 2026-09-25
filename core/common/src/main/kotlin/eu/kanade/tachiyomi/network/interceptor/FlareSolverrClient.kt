package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Cookie
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jsoup.Jsoup
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
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
    private val networkPreferences: NetworkPreferences,
) {

    internal val flareSolverrClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // Above the 60s maxTimeout every command asks the server for, so the server has room to
            // report its own failure rather than the read expiring at the same moment and the failure
            // reading as an unreachable server. The 90s cap stays: the solve runs inside
            // NetworkHelper's client, whose own callTimeout is two minutes.
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            // Basic auth for a proxy in front of the server, applied here rather than at each call
            // so no request can be left out. Missing it on the banner probe alone would 401 that
            // probe, which latches [fsSessionsSupported] off for the rest of the run.
            // The API never redirects. One means the address is wrong, most often http behind a proxy
            // that forces https, and following it would drop the login and report a sign-in failure.
            .followRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val header = flareSolverrLoginFor(
                    request.url.toString(),
                    networkPreferences.flareSolverrUrl.get(),
                    networkPreferences.flareSolverrUsername.get(),
                    networkPreferences.flareSolverrPassword.get(),
                )
                chain.proceed(
                    if (header == null) request else request.newBuilder().header("Authorization", header).build(),
                )
            }
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

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

    // The login a failed probe ran under: a proxy 401 before the reader saved theirs says nothing once they have.
    @Volatile private var sessionlessUnder: String? = null

    private fun currentLogin(): String = flareSolverrAuthHeader(
        networkPreferences.flareSolverrUsername.get().trim(),
        networkPreferences.flareSolverrPassword.get(),
    ).orEmpty()

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
     * return its fully-fetched response. One solve per host at a time is [WebViewInterceptor]'s
     * per-host lock, which every caller holds.
     */
    fun resolve(flareSolverrUrl: String, request: Request): Response {
        cookieManager.remove(request.url, COOKIE_NAMES, 0)
        return resolveWithFlareSolverr(flareSolverrUrl, request)
    }

    /**
     * Connectivity check for the settings "Test" button: a sessionless solve of google.com.
     * Reports the failure as a case rather than a sentence, so the settings screen can name it in
     * the reader's own language and still show the technical text. Runs off the main thread.
     */
    suspend fun test(flareSolverrUrl: String): FlareSolverrTestResult = withContext(Dispatchers.IO) {
        val command = buildJsonObject {
            put("cmd", "request.get")
            put("url", "https://www.google.com/")
            put("maxTimeout", 60000)
        }
        val body = json.encodeToString(JsonObject.serializer(), command)
            .toRequestBody(JSON_MEDIA_TYPE)
        // A restored address skipped the settings field's check, so it may not parse at all.
        val address = "${flareSolverrUrl.trimEnd('/')}/v1".toHttpUrlOrNull()
            ?: return@withContext FlareSolverrTestResult.Failure(FlareSolverrTestFailure.UNREACHABLE, "not an address")
        val req = Request.Builder()
            .url(address)
            .post(body)
            .build()
        // Whether the call got as far as a connection, so a timeout can be told from an unreachable address.
        var connected = false
        val listener = object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                connected = true
            }
        }
        val text = try {
            flareSolverrClient.newBuilder().eventListener(listener).build().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext FlareSolverrTestResult.Failure(
                        FlareSolverrTestFailure.ofStatus(resp.code),
                        listOfNotNull("HTTP ${resp.code} ${resp.message}".trim(), resp.header("Location"))
                            .joinToString(" -> "),
                    )
                }
                resp.body.string()
            }
        } catch (e: IOException) {
            return@withContext FlareSolverrTestResult.Failure(
                FlareSolverrTestFailure.ofException(e, connected),
                e.message ?: e.toString(),
            )
        }
        val result = try {
            json.decodeFromString(FlareSolverrResponse.serializer(), text)
        } catch (e: SerializationException) {
            // Something served that address, but not the FlareSolverr API: a proxy error page, a
            // sign-in page, or an unrelated service.
            return@withContext FlareSolverrTestResult.Failure(
                FlareSolverrTestFailure.NOT_A_SOLVER,
                e.message ?: e.toString(),
            )
        }
        if (result.status != "ok") {
            return@withContext FlareSolverrTestResult.Failure(
                FlareSolverrTestFailure.SOLVE_FAILED,
                result.message.ifBlank { "status: ${result.status}" },
            )
        }
        val solution = result.solution
            ?: return@withContext FlareSolverrTestResult.Failure(
                FlareSolverrTestFailure.SOLVE_FAILED,
                "no solution in the response",
            )
        if (solution.status !in 200..299) {
            return@withContext FlareSolverrTestResult.Failure(
                FlareSolverrTestFailure.SOLVE_FAILED,
                "solution status: ${solution.status}",
            )
        }
        FlareSolverrTestResult.Success
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
        if (!fsSessionsSupported && sessionlessUnder != currentLogin()) fsSessionsSupported = true
        if (!fsSessionsSupported) return null
        fsSessionId?.let { return it }
        return synchronized(fsSessionLock) {
            if (!fsSessionsSupported) return@synchronized null
            fsSessionId?.let { return@synchronized it }
            // Only real FlareSolverr implements sessions. Byparr is sessionless and 500s on
            // sessions.create (it has no url to navigate), spamming its console with a stack trace.
            // Probe the root banner so we attempt sessions.create only on FlareSolverr.
            if (!flareSolverrSupportsSessions(flareSolverrUrl)) {
                sessionlessUnder = currentLogin()
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
                sessionlessUnder = currentLogin()
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
        // would GET the URL and return the wrong page. FS can only send postData as
        // application/x-www-form-urlencoded, so [flareSolverrPostData] re-encodes the body's
        // fields. Build the command via the JSON DSL so the body can't break the envelope.
        val isPost = request.method.equals("POST", ignoreCase = true)
        val postData = request.body?.takeIf { isPost }?.let(::flareSolverrPostData)
        val forwarded = if (isPrivateChannel(flareSolverrUrl)) {
            cookiesToForward(cookieManager.get(request.url), request.header("Cookie"), request.url)
        } else {
            emptyList()
        }
        val command = flareSolverrCommand(targetUrl, isPost, postData, sessionId, forwarded)
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
        cookiesToKeep(solution.cookies, forwarded).forEach { fsCookie ->
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

/** What the settings "Test" button learned about the configured server. */
sealed interface FlareSolverrTestResult {
    data object Success : FlareSolverrTestResult

    /** [detail] is the untranslated technical text: the status line, or the exception's message. */
    data class Failure(val reason: FlareSolverrTestFailure, val detail: String) : FlareSolverrTestResult
}

enum class FlareSolverrTestFailure {
    AUTH_REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    SOLVER_DOWN,
    HTTP_ERROR,
    TIMED_OUT,
    UNREACHABLE,
    NOT_A_SOLVER,
    SOLVE_FAILED,
    LOGIN_NOT_PRIVATE,
    REDIRECTED,
    ;

    companion object {
        /** A proxy in front of the server answers before it does, so these statuses are about the
         *  proxy rather than the solve: 407 is a proxy's own challenge, 401 a password on the path,
         *  and a gateway status means the proxy is up while the solver behind it is not, which a
         *  cold solver produces for its whole startup. */
        fun ofStatus(code: Int): FlareSolverrTestFailure = when (code) {
            401, 407 -> AUTH_REQUIRED
            403 -> FORBIDDEN
            404 -> NOT_FOUND
            502, 503, 504 -> SOLVER_DOWN
            in 300..399 -> REDIRECTED
            else -> HTTP_ERROR
        }

        // A timeout once [connected] is not an unreachable address: something answered and then took
        // too long. One while connecting is: nothing answered at all, as for a LAN address off the LAN.
        fun ofException(e: IOException, connected: Boolean): FlareSolverrTestFailure = when {
            e is FlareSolverrLoginRefusedException -> LOGIN_NOT_PRIVATE
            e is InterruptedIOException && connected -> TIMED_OUT
            else -> UNREACHABLE
        }
    }
}

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private const val JSON_CONTENT_TYPE = "application/json; charset=UTF-8"
private val COOKIE_NAMES = listOf("cf_clearance")

/**
 * Whether a secret may travel to the FlareSolverr at [flareSolverrUrl]: over https, or in the clear
 * only to the user's own network or mesh VPN. The site's cookies and the proxy login both go through this.
 */
fun isPrivateChannel(flareSolverrUrl: String): Boolean {
    val url = flareSolverrUrl.trim().toHttpUrlOrNull() ?: return false
    if (url.isHttps) return true
    val host = url.host.lowercase()
    if (host == "localhost" || ('.' !in host && ':' !in host)) return true
    if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
    if (host.endsWith(".ts.net")) return url.port !in TAILSCALE_PUBLIC_PORTS
    if (MESH_SUFFIXES.any { host.endsWith(it) }) return true
    if (':' in host) return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4) return false
    val (a, b) = octets
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
        (a == 169 && b == 254) || (a == 100 && b in 64..127)
}

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

// NetBird's peer names, hosted and its self-hosted default, which resolve only inside the mesh.
private val MESH_SUFFIXES = listOf(".netbird.cloud", ".netbird.selfhosted")

// A Tailscale name resolves only inside the tailnet unless Funnel publishes it, to relays taking https
// on 443, 8443 and 10000; 80 too, since how a relay answers plain http there is undocumented.
private val TAILSCALE_PUBLIC_PORTS = setOf(80, 443, 8443, 10000)

/**
 * The cookies a request carries, as OkHttp would send them: the jar's for [url], or when the jar has
 * none, the Cookie header the source set on the request itself.
 */
internal fun cookiesToForward(jar: List<Cookie>, cookieHeader: String?, url: HttpUrl): List<Cookie> =
    jar.ifEmpty {
        cookieHeader.orEmpty().split(';').mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty() || '=' !in pair) return@mapNotNull null
            Cookie.Builder().name(name).value(pair.substringAfter('=').trim()).domain(url.host).build()
        }
    }

/**
 * The solver's cookies worth storing: not one it only echoed back from [forwarded], which returns
 * without the flags the app's copy has and would be saved beside it, wider and without Secure.
 */
internal fun cookiesToKeep(solved: List<FlareSolverrCookie>, forwarded: List<Cookie>): List<FlareSolverrCookie> =
    solved.filterNot { fs -> forwarded.any { it.name == fs.name && it.value == fs.value } }

/**
 * A POST body as the url-encoded form FlareSolverr posts. A form body, or a multipart one of plain
 * text fields (an LN plugin's FormData), is re-encoded field by field: sent raw, a multipart body
 * reaches the site as one mangled field. Spaces go as `%20`, since the solver keeps a `+` literal.
 * Anything else, a file part included, is sent as its raw text, which is all the solver can carry.
 */
internal fun flareSolverrPostData(body: RequestBody): String {
    val fields = when (body) {
        is FormBody -> (0 until body.size).map { body.name(it) to body.value(it) }
        is MultipartBody -> body.parts.map { part ->
            val disposition = part.headers?.get("Content-Disposition").orEmpty()
            val name = FORM_FIELD_NAME.find(disposition)?.groupValues?.get(1)
            if (name == null || "filename=" in disposition) return rawText(body)
            name.replace("%22", "\"") to rawText(part.body)
        }
        else -> return rawText(body)
    }
    return fields.joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" }
}

private val FORM_FIELD_NAME = Regex("""name="([^"]*)"""")

private fun rawText(body: RequestBody): String = Buffer().also { body.writeTo(it) }.readUtf8()

private fun formEncode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/**
 * The command for one FlareSolverr request. The site's cookies from the app's jar go along, since
 * the solver's browser holds none of them and a page behind a login would otherwise come back signed
 * out. Cloudflare's own stay behind: the solver earns its own, and an app-side one is bound to a
 * fingerprint its browser does not have. Internal so which cookies travel is unit-testable.
 */
internal fun flareSolverrCommand(
    targetUrl: String,
    isPost: Boolean,
    postData: String?,
    sessionId: String?,
    cookies: List<Cookie>,
): JsonObject = buildJsonObject {
    put("cmd", if (isPost) "request.post" else "request.get")
    put("url", targetUrl)
    if (isPost) put("postData", postData ?: "")
    if (sessionId != null) put("session", sessionId)
    put("maxTimeout", 60000)
    val forwarded = cookies.filterNot { it.name.startsWith("cf_") || it.name.startsWith("__cf") }
    if (forwarded.isNotEmpty()) {
        putJsonArray("cookies") {
            forwarded.forEach { cookie ->
                addJsonObject {
                    put("name", cookie.name)
                    put("value", cookie.value)
                }
            }
        }
    }
}

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
