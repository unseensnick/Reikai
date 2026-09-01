package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
    // RK -->
    private val networkPreferences: NetworkPreferences,
    private val flareSolverr: FlareSolverrClient,
    // RK <--
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        // Checking the cf-mitigated header is the official way to detect a Cloudflare challenge:
        // https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/
        return response.header("cf-mitigated") == "challenge" && response.header("Server") in SERVER_CHECK
    }

    override fun getNonce(url: HttpUrl): String? = cookieManager.get(url)
        .firstOrNull { it.name == "cf_clearance" }
        ?.value

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
        nonce: String?,
    ): Response? {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }

            // RK -->
            val host = request.url.host
            val flareSolverrUrl = networkPreferences.flareSolverrUrl.get().trim()
            val fsActive = networkPreferences.enableFlareSolverr.get() && flareSolverrUrl.isNotBlank()

            // FlareSolverr returns a fully-fetched response, so serve it directly. Replaying its
            // cookies through OkHttp doesn't work for hosts on Cloudflare's stricter bot-management
            // tier (cf_clearance is bound to a TLS / __cf_bm fingerprint OkHttp can't reproduce).
            // A null return means a sibling thread did the solve; fall through to a normal retry
            // with whatever the cookie jar holds.
            if (fsActive && flareSolverr.shouldSkipWebView(host)) {
                flareSolverr.resolve(flareSolverrUrl, request)?.let { return it }
            } else {
                try {
                    // One solve per host is the base class's job now; a sibling that queued behind
                    // this one re-checks the jar and never reaches here.
                    resolveWithWebView(request, oldCookie)
                } catch (e: CloudflareBypassException) {
                    if (!fsActive) throw e
                    // Don't re-pay the 30s WebView timeout on later requests to a host the WebView
                    // can't clear: mark it so subsequent requests go straight to FlareSolverr.
                    flareSolverr.markWebViewUnsolvable(host)
                    flareSolverr.resolve(flareSolverrUrl, request)?.let { return it }
                }
            }

            // WebView path: retry the request normally. Returning null lets the base class do that
            // outside the per-host write lock, so the next challenged request is not queued behind
            // this one's retry. A FlareSolverr-pinned host is the exception: the application
            // interceptor chain doesn't re-run on chain.proceed() from inside an interceptor, so the
            // pinned UA has to be applied here, and that retry does hold the lock.
            val pinnedUa = flareSolverr.pinnedUserAgentFor(host) ?: return null
            return chain.proceed(request.newBuilder().header("User-Agent", pinnedUa).build())
            // RK <--
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            // RK: carry the blocked URL so the caller can open that page for the user to solve. The
            //     site root often is not challenged at all, which leaves nothing to clear.
            throw CloudflareBypassIOException(
                context.stringResource(MR.strings.information_cloudflare_bypass_failure),
                request.url.toString(),
                e,
            )
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val headers = parseHeaders(originalRequest.headers)
        // RK: an origin rule without a port matches only the scheme's default, so a source on a
        //     custom one has to spell it out or the solver's probe would never run on its page.
        val origin = with(originalRequest.url) {
            if (port == HttpUrl.defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"
        }
        // RK: the solver presses the checkbox an interactive challenge is waiting on, so with it
        //     armed that challenge is no longer a reason to give up. Off by default, and with no
        //     window it arms only when the separate background switch is on.
        //     It is null until the solver arms, and is the only thing the outcome is read from.
        val solve = AtomicReference<TurnstileSolver.Solve?>()
        val solverWanted = networkPreferences.enableTurnstileSolver.get()

        executor.execute {
            webview = createWebView(originalRequest)

            webview.addJavascriptInterface(
                object {
                    @Suppress("unused")
                    @JavascriptInterface
                    fun interactiveDetected() {
                        // The challenge cannot be solved non-interactively, abort.
                        // RK: only while the solver is off. Armed, it takes this event from its own
                        //     probe where it has one, and through challengeEvent below where it does
                        //     not, so an armed solve never gives up here.
                        if (solve.get() == null) latch.countDown()
                    }

                    // RK: Cloudflare reports a challenge it has given up on. Without this the
                    //     request sits out the full latch timeout for a result already decided.
                    //     Only trusted while the solver is off: it keeps pressing through a failed
                    //     round, and Cloudflare reissues after one often enough to matter.
                    @Suppress("unused")
                    @JavascriptInterface
                    fun challengeFailed() {
                        logcat { "Turnstile[${originalRequest.url.host}]: challenge failed" }
                        if (solve.get() == null) latch.countDown()
                    }

                    // RK: every event Cloudflare posts, acted on by nothing. The two handlers above
                    //     cover the only ones this decides anything from, so a solve that stalls
                    //     leaves no trace of what the challenge was actually doing. `complete` in
                    //     particular is Cloudflare reporting a solve it accepted, which is a better
                    //     signal than watching the markup go, and this is how we learn whether it
                    //     reaches an interstitial at all. See the rework design in the plan doc.
                    @Suppress("unused")
                    @JavascriptInterface
                    fun challengeEvent(event: String) {
                        logcat { "Turnstile[${originalRequest.url.host}]: cf event $event" }
                        // RK: the solve's only input on a WebView with no isolated world to poll
                        //     from. It ignores this while a probe is watching, so the two never
                        //     both drive it.
                        solve.get()?.report(event)
                    }
                },
                "mihon",
            )

            // RK: arming has to precede the load, since an injected script only reaches documents
            //     created after it is registered.
            if (solverWanted) {
                val armed = TurnstileSolver.attach(
                    webView = webview,
                    host = originalRequest.url.host,
                    origin = origin,
                    backgroundEnabled = networkPreferences.enableTurnstileBackgroundSolver.get(),
                    // Arming suppresses the interactive and failed aborts below, so the solve owns
                    // giving up on its own path; without this the request waits out the full latch.
                    // Reports whether there was still a wait to release, so a timer that fires after
                    // the request was served some other way stays quiet.
                    onGiveUp = {
                        val waiting = latch.count > 0L
                        if (waiting) latch.countDown()
                        waiting
                    },
                ) {
                    // The retry needs the clearance cookie, and it lands after the challenge page
                    // goes, so a solve is only accepted once both have happened.
                    val cleared = cookieManager.get(origRequestUrl.toHttpUrl())
                        .firstOrNull { it.name == "cf_clearance" }
                    (cleared != null && cleared != oldCookie).also { accepted ->
                        if (accepted) {
                            cloudflareBypassed = true
                            latch.countDown()
                        }
                    }
                }
                solve.set(armed)
                if (armed == null) {
                    logcat {
                        "Turnstile[${originalRequest.url.host}]: not armed; the webview is too old, " +
                            "or there is no window and the background switch is off"
                    }
                }
            }

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    // RK: with the solver running, a fresh cf_clearance is not proof of anything.
                    //     Cloudflare hands one out on a challenge it has not accepted, which ended
                    //     three test solves early with a 403 on the retry; the solver reports when
                    //     the challenge markup is actually gone instead.
                    if (solve.get() == null && isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }

                    if (url == origRequestUrl) {
                        if (!challengeFound) {
                            // The first request didn't return the challenge, abort.
                            latch.countDown()
                        } else {
                            // Listen for an interactiveBegin event
                            view.evaluateJavascript(
                                """
                                    addEventListener("message", ({data}) => {
                                        if (data?.source === "cloudflare-challenge" && data?.event === "interactiveBegin") {
                                            mihon.interactiveDetected();
                                        }
                                        // RK -->
                                        if (data?.source === "cloudflare-challenge") {
                                            mihon.challengeEvent(String(data?.event));
                                        }
                                        if (data?.source === "cloudflare-challenge" && data?.event === "fail") {
                                            mihon.challengeFailed();
                                        }
                                        // RK <--
                                    })
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                }

                // RK: a dead renderer never finishes the page, so the request would sit out the
                //     latch timeout, and returning false from here kills the app process.
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    latch.countDown()
                    return true
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.responseHeaders["cf-mitigated"] == "challenge") {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        latch.awaitFor30Seconds()

        // RK: a solve the WebView performed but never reported still leaves its clearance in the
        //     jar, so ask the jar before giving up. Measured on a source whose two requests raced:
        //     one retried to a 200 while the other threw, having solved the challenge itself.
        //     Gated on the solver having watched the interstitial go, because a clearance on its own
        //     proves nothing: Cloudflare issues one on a round it refused, and trusting that turned
        //     three honest failures into 403s with no Open in WebView offered.
        if (!cloudflareBypassed && solverWanted && solve.get()?.phase == TurnstileSolver.Solve.Phase.Verified) {
            val cleared = cookieManager.get(origRequestUrl.toHttpUrl())
                .firstOrNull { it.name == "cf_clearance" }
            if (cleared != null && cleared != oldCookie) {
                cloudflareBypassed = true
                logcat { "Turnstile[${originalRequest.url.host}]: cleared without a report, retrying" }
            }
        }

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                TurnstileSolver.detach(this) // RK
                stopLoading()
                destroy()
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed) {
            // RK: Cloudflare hands out a clearance on a round it refused, so leaving one behind makes
            //     a sibling queued on this host skip its own solve and retry straight into a 403. A
            //     failed solve leaves nothing behind. Taken from mihonapp/mihon#3858.
            cookieManager.remove(originalRequest.url, COOKIE_NAMES, 0)
            // Prompt user to update WebView if it seems too outdated
            if (isWebViewOutdated) {
                context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
            }

            throw CloudflareBypassException()
        }
    }
}

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()

// RK: thrown when the bypass gives up, carrying the URL the challenge blocked.
class CloudflareBypassIOException(
    message: String?,
    val url: String,
    cause: Throwable?,
) : IOException(message, cause)

// RK: the failure can arrive wrapped by a source, so walk the cause chain for the blocked URL.
fun Throwable.cloudflareBlockedUrl(): String? =
    generateSequence(this, Throwable::cause)
        .filterIsInstance<CloudflareBypassIOException>()
        .firstOrNull()
        ?.url
