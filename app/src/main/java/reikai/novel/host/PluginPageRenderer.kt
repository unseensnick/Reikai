package reikai.novel.host

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.interceptor.isSameOrigin
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.lang.withUIContext
import kotlin.coroutines.resume

/**
 * LNReader's `fetchViaWebview`: loads a page of the plugin's own site in a WebView no window shows,
 * waits for [PageRequest.waitForSelector] to hold text, and hands back the HTML, for a site whose own
 * script must run (a verification step) before its text exists. One page at a time, as upstream.
 * The WebView shares the app's cookies, so a sign-in passed in the in-app browser carries over.
 * [LnPluginHost] owns the one instance, so the queue spans every plugin.
 */
internal class PluginPageRenderer(private val appContext: Context) {

    private val mutex = Mutex()

    suspend fun render(url: HttpUrl, request: PageRequest): RenderedPage {
        val timeoutMs = request.timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // As upstream, a call still queued when its time runs out is refused; the page it loads gets its full time.
        withTimeoutOrNull(timeoutMs) { mutex.lock() } ?: throw LnPluginException(TIMED_OUT)
        try {
            val page = withUIContext { renderOnMain(url, request, timeoutMs) }
            // getHtml hands back the page as a JSON string literal, decoded here off the main thread.
            val html = page?.let {
                withContext(Dispatchers.Default) {
                    LnPluginHost.JSON.decodeFromString(String.serializer(), it.htmlJson)
                }
            }
            return RenderedPage(
                ok = html != null,
                url = page?.url ?: url.toString(),
                html = html.orEmpty(),
                title = page?.title.orEmpty(),
            )
        } finally {
            mutex.unlock()
        }
    }

    /** The page as the WebView left it, its HTML still the JSON string literal getHtml returns. */
    private class LoadedPage(val url: String, val htmlJson: String, val title: String)

    private suspend fun renderOnMain(url: HttpUrl, request: PageRequest, timeoutMs: Long): LoadedPage? {
        // Why the page failed, as the WebView reports it; each one rejects the plugin's call, as upstream.
        var failure: String? = null
        // An empty selector is no selector, as upstream reads it.
        val selector = request.waitForSelector?.takeIf { it.isNotEmpty() }
        val waitsForText = selector != null
        var loaded = false
        val webView = WebView(appContext).apply {
            setDefaultSettings()
            request.userAgent?.takeIf { it.isNotBlank() }?.let { settings.userAgentString = it }
            webViewClient = object : WebViewClient() {
                // Subframes are let through: a verification widget loads its own frame from another site.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val leaves = request.isForMainFrame && !request.url.toString().isOn(url)
                    if (leaves) failure = failure ?: "fetchViaWebview: the page left ${url.host}"
                    return leaves
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failure = failure ?: "fetchViaWebview: ${error.description}"
                }

                // With a selector the text decides, as upstream: a visitor check can answer 403, then fill the page.
                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && response.statusCode >= 400 && !waitsForText) {
                        failure = failure ?: "fetchViaWebview: HTTP ${response.statusCode}"
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failure = failure ?: "fetchViaWebview: the page stopped rendering"
                    return true
                }
            }
        }
        return try {
            webView.loadUrl(url.toString())
            val page = withTimeoutOrNull(timeoutMs) {
                // Checked before each poll: only destroy may be called on a WebView whose renderer is gone.
                while (true) {
                    failure?.let { throw LnPluginException(it) }
                    val state = webView.readiness(selector)
                    loaded = state != NOT_LOADED
                    if (state == READY) break
                    delay(POLL_MS)
                }
                delay(request.settleMs.coerceIn(0L, MAX_SETTLE_MS))
                failure?.let { throw LnPluginException(it) }
                // A redirect the guard stopped leaves no page of the site loaded.
                val at = webView.url
                if (at?.isOn(url) != true) throw LnPluginException("fetchViaWebview: the page left ${url.host}")
                LoadedPage(url = at, htmlJson = webView.getHtml(), title = webView.title.orEmpty())
            }
            // Only a loaded page whose selector never filled is the plugin's ok false; anything else is an error.
            page ?: if (waitsForText && loaded) null else throw LnPluginException(TIMED_OUT)
        } finally {
            webView.destroy()
        }
    }

    private suspend fun WebView.readiness(selector: String?): Int = suspendCancellableCoroutine { cont ->
        val target = selector?.let { LnPluginHost.JSON.encodeToString(String.serializer(), it) } ?: "null"
        evaluateJavascript(READY_SCRIPT.replace(SELECTOR_SLOT, target)) { cont.resume(it?.toIntOrNull() ?: NOT_LOADED) }
    }

    private fun String.isOn(site: HttpUrl): Boolean = toHttpUrlOrNull()?.let { isSameOrigin(it, site) } == true

    /** What a plugin passes to `fetchViaWebview`, with LNReader's defaults. */
    @Serializable
    data class PageRequest(
        val waitForSelector: String? = null,
        val timeoutMs: Long = 15_000L,
        val settleMs: Long = 300L,
        val userAgent: String? = null,
    )

    /** LNReader's reply: [ok] is false when the selector's text never showed in time, and [html] is then empty. */
    @Serializable
    data class RenderedPage(val ok: Boolean, val url: String, val html: String, val title: String)

    private companion object {
        const val POLL_MS = 250L
        const val MIN_TIMEOUT_MS = 1_000L

        // A plugin's whole call has 180 s, and every other plugin's page waits behind this one.
        const val MAX_TIMEOUT_MS = 60_000L
        const val MAX_SETTLE_MS = 5_000L
        const val SELECTOR_SLOT = "__SELECTOR__"
        const val TIMED_OUT = "fetchViaWebview: timed out waiting for page"

        // What READY_SCRIPT answers: the page is still loading, it loaded without the selector's text, or it is ready.
        const val NOT_LOADED = 0
        const val LOADED = 1
        const val READY = 2

        // The blank page a WebView starts on is complete while the real one is still loading, so it never counts.
        // An invalid selector throws, which reads as not ready until the timeout, as upstream.
        const val READY_SCRIPT = "(function(){if(location.href==='about:blank')return $NOT_LOADED;" +
            "if(document.readyState!=='complete')return $NOT_LOADED;" +
            "var s=$SELECTOR_SLOT;if(!s)return $READY;var m=document.querySelectorAll(s);" +
            "for(var i=0;i<m.length;i++){if((m[i].textContent||'').trim())return $READY;}return $LOADED;})()"
    }
}

/**
 * The page a plugin may render: an address on [site], the plugin's own declared site, or nothing at
 * all while the plugin declares none. Plugin code is untrusted, so this is enforced here, not in JS.
 */
internal fun pluginPageUrl(requested: String, site: HttpUrl?): HttpUrl {
    val allowed = site ?: throw LnPluginException("fetchViaWebview: the plugin declares no site")
    val url = requested.toHttpUrlOrNull() ?: throw LnPluginException("fetchViaWebview: not a web address")
    if (!isSameOrigin(url, allowed)) throw LnPluginException("fetchViaWebview: ${url.host} is not the plugin's site")
    return url
}
