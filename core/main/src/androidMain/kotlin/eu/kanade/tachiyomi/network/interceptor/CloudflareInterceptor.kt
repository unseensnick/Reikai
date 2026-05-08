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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import yokai.i18n.MR
import yokai.util.lang.getString

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
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

            val flareSolverrUrl = networkPreferences.flareSolverrUrl().get().trim()
            var flareSolverrSucceeded = false

            if (flareSolverrUrl.isNotBlank()) {
                try {
                    resolveWithFlareSolverr(flareSolverrUrl, request)
                    flareSolverrSucceeded = true
                    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        executor.execute { context.toast("FlareSolverr bypassed Cloudflare") }
                    }
                } catch (_: Exception) {
                    // FlareSolverr failed — fall through to WebView
                }
            }

            if (!flareSolverrSucceeded) {
                val oldCookie = cookieManager.get(request.url)
                    .firstOrNull { it.name == "cf_clearance" }
                resolveWithWebView(request, oldCookie)
            }

            // cf_clearance is bound to the UA that solved the challenge. After FlareSolverr
            // succeeds it saves its browser UA to preferences, so we must use that updated UA
            // here — the original request still carries the old UA from UserAgentInterceptor.
            val retryRequest = if (flareSolverrSucceeded) {
                request.newBuilder()
                    .header("User-Agent", defaultUserAgentProvider())
                    .build()
            } else {
                request
            }

            return chain.proceed(retryRequest)
        } catch (e: CloudflareBypassException) {
            throw IOException(context.getString(MR.strings.failed_to_bypass_cloudflare), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun resolveWithFlareSolverr(flareSolverrUrl: String, request: Request) {
        val targetUrl = request.url.toString()

        val body = """{"cmd":"request.get","url":"$targetUrl","maxTimeout":60000,"returnOnlyCookies":true}"""
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

        val cookies = result.solution.cookies.mapNotNull { it.toOkHttpCookie(request.url.host) }
        cookieManager.saveFromResponse(request.url, cookies)

        if (result.solution.userAgent.isNotBlank()) {
            networkPreferences.defaultUserAgent().set(result.solution.userAgent)
        }
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
    fun toOkHttpCookie(requestHost: String): Cookie? {
        return try {
            val cookieDomain = domain.trimStart('.').ifBlank { requestHost }
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(cookieDomain)
                .path(path)
                .apply {
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                    if (expires > 0) expiresAt((expires * 1000).toLong())
                }
                .build()
        } catch (_: Exception) {
            null
        }
    }
}
