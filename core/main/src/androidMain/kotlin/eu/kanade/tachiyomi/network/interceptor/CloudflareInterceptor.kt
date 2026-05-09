package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.system.WebViewClientCompat
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import yokai.i18n.MR
import yokai.util.lang.getString

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
    private val networkPreferences: NetworkPreferences,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

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

    fun pinnedUserAgentFor(host: String): String? = fsPinByHost[host]

    override fun shouldIntercept(response: Response): Boolean {
        return if (response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK) {
            val document = Jsoup.parse(
                response.peekBody(Long.MAX_VALUE).string(),
                response.request.url.toString(),
            )
            document.getElementById("challenge-error-title") != null ||
                document.getElementById("challenge-error-text") != null
        } else {
            false
        }
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }

            try {
                resolveWithWebView(request, oldCookie)
            } catch (e: CloudflareBypassException) {
                val flareSolverrUrl = networkPreferences.flareSolverrUrl().get().trim()
                if (flareSolverrUrl.isBlank()) throw e

                // FlareSolverr returns a fully-fetched response — serve it directly. Replaying
                // its cookies through OkHttp doesn't work for hosts on Cloudflare's stricter
                // bot-management tier (cf_clearance is bound to TLS / __cf_bm fingerprint that
                // OkHttp can't reproduce). A null return means another thread did the solve;
                // fall through to a normal retry with whatever the cookie jar holds.
                val fsResponse = resolveWithFlareSolverrDedup(flareSolverrUrl, request)
                if (fsResponse != null) return fsResponse
            }

            // WebView path (or sibling-solve fallback): retry the request normally. The
            // application interceptor chain doesn't re-run on chain.proceed() from inside an
            // interceptor, so apply any FS-pinned UA directly here.
            val retryRequest = fsPinByHost[request.url.host]?.let { pinnedUa ->
                request.newBuilder().header("User-Agent", pinnedUa).build()
            } ?: request

            return chain.proceed(retryRequest)
        } catch (e: CloudflareBypassException) {
            throw IOException(context.getString(MR.strings.failed_to_bypass_cloudflare), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun resolveWithFlareSolverrDedup(flareSolverrUrl: String, request: Request): Response? {
        val host = request.url.host

        while (true) {
            val existing = pendingFSSolves[host]
            if (existing != null) {
                // Another thread is already solving for this host — wait for it, then return null
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
            // Another thread won the race between our check and putIfAbsent — loop and wait.
        }
    }

    private fun resolveWithFlareSolverr(flareSolverrUrl: String, request: Request): Response {
        val targetUrl = request.url.toString()

        // Full-response mode (returnOnlyCookies defaults to false). FS returns the page body its
        // headless Chrome fetched, so we can serve it directly and avoid replaying cf_clearance
        // through OkHttp — a path Cloudflare's TLS / __cf_bm fingerprinting often rejects.
        val body = """{"cmd":"request.get","url":"$targetUrl","maxTimeout":60000}"""
            .toRequestBody("application/json".toMediaType())

        val fsRequest = Request.Builder()
            .url("${flareSolverrUrl.trimEnd('/')}/v1")
            .post(body)
            .build()

        val fsResponse = flareSolverrClient.newCall(fsRequest).execute()
        val fsBody = fsResponse.body.string()

        if (!fsResponse.isSuccessful) {
            throw IOException("FlareSolverr returned HTTP ${fsResponse.code}")
        }

        val result = json.decodeFromString<FlareSolverrResponse>(fsBody)
        if (result.status != "ok") {
            throw IOException("FlareSolverr error: ${result.message}")
        }

        if (result.solution.status !in 200..299) {
            throw IOException("FlareSolverr solution status: ${result.solution.status}")
        }

        // Best-effort: stash cookies + UA so unrelated future requests to this host can succeed
        // without re-invoking FS. The current request's correctness comes from the synthetic
        // response we build below, not from these.
        result.solution.cookies.forEach { fsCookie ->
            cookieManager.saveCookieString(request.url, fsCookie.toRawCookieString(request.url.host))
        }
        if (result.solution.userAgent.isNotBlank()) {
            fsPinByHost[request.url.host] = result.solution.userAgent
        }

        return buildResponseFromFlareSolverr(request, result.solution)
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            webview = createWebView(originalRequest)

            webview.webViewClient = object : WebViewClientCompat() {
                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }

                    if (url == origRequestUrl && !challengeFound) {
                        latch.countDown()
                    }
                }

                override fun onReceivedErrorCompat(
                    view: WebView,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String,
                    isMainFrame: Boolean,
                ) {
                    if (isMainFrame) {
                        if (errorCode in ERROR_CODES) {
                            challengeFound = true
                        } else {
                            latch.countDown()
                        }
                    }
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                stopLoading()
                destroy()
            }
        }

        if (!cloudflareBypassed) {
            if (isWebViewOutdated) {
                context.toast(MR.strings.please_update_webview, Toast.LENGTH_LONG)
            }

            throw CloudflareBypassException()
        }
    }
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()

@Serializable
private data class FlareSolverrResponse(
    val status: String,
    val message: String = "",
    val solution: FlareSolverrSolution,
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
