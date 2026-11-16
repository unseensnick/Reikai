package reikai.novel.host

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.interceptor.isSameOrigin
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    suspend fun render(url: HttpUrl, request: PageRequest): RenderedPage = mutex.withLock {
        val page = withUIContext { renderOnMain(url, request) }
        // getHtml hands back the page as a JSON string literal, decoded here off the main thread.
        val html = page?.let {
            withContext(Dispatchers.Default) { LnPluginHost.JSON.decodeFromString(String.serializer(), it.html) }
        }
        RenderedPage(
            ok = html != null,
            url = page?.url ?: url.toString(),
            html = html.orEmpty(),
            title = page?.title.orEmpty(),
        )
    }

    private suspend fun renderOnMain(url: HttpUrl, request: PageRequest): RenderedPage? {
        var rendererGone = false
        val webView = WebView(appContext).apply {
            setDefaultSettings()
            webViewClient = object : WebViewClient() {
                // Subframes are let through: a verification widget loads its own frame from another site.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !request.url.toString().isOn(url)

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    rendererGone = true
                    return true
                }
            }
        }
        return try {
            webView.loadUrl(url.toString())
            withTimeoutOrNull(request.timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)) {
                while (!webView.isReady(request.waitForSelector)) {
                    // Only destroy may be called on a WebView whose renderer is gone.
                    if (rendererGone) return@withTimeoutOrNull null
                    delay(POLL_MS)
                }
                delay(request.settleMs.coerceIn(0L, MAX_SETTLE_MS))
                // A redirect the guard stopped leaves no page of the site loaded, which must not read as ok.
                val at = webView.url
                if (rendererGone || at?.isOn(url) != true) return@withTimeoutOrNull null
                RenderedPage(ok = true, url = at, html = webView.getHtml(), title = webView.title.orEmpty())
            }
        } finally {
            webView.destroy()
        }
    }

    private suspend fun WebView.isReady(selector: String?): Boolean = suspendCancellableCoroutine { cont ->
        val target = selector?.let { LnPluginHost.JSON.encodeToString(String.serializer(), it) } ?: "null"
        evaluateJavascript(READY_SCRIPT.replace(SELECTOR_SLOT, target)) { cont.resume(it == "true") }
    }

    private fun String.isOn(site: HttpUrl): Boolean = toHttpUrlOrNull()?.let { isSameOrigin(it, site) } == true

    /** What a plugin passes to `fetchViaWebview`, with LNReader's defaults. */
    @Serializable
    data class PageRequest(
        val waitForSelector: String? = null,
        val timeoutMs: Long = 15_000L,
        val settleMs: Long = 300L,
    )

    /** LNReader's reply: [ok] is false when the page never showed its text in time, and [html] is then empty. */
    @Serializable
    data class RenderedPage(val ok: Boolean, val url: String, val html: String, val title: String)

    private companion object {
        const val POLL_MS = 250L
        const val MIN_TIMEOUT_MS = 1_000L

        // A plugin's whole call has 180 s, and every other plugin's page waits behind this one.
        const val MAX_TIMEOUT_MS = 60_000L
        const val MAX_SETTLE_MS = 5_000L
        const val SELECTOR_SLOT = "__SELECTOR__"

        // An invalid selector throws, which reads as not ready until the timeout, as upstream.
        const val READY_SCRIPT = "(function(){if(document.readyState!=='complete')return false;" +
            "var s=$SELECTOR_SLOT;if(!s)return true;var m=document.querySelectorAll(s);" +
            "for(var i=0;i<m.length;i++){if((m[i].textContent||'').trim())return true;}return false;})()"
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
