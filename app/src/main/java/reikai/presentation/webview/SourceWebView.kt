package reikai.presentation.webview

import mihon.core.navigation.WebViewRoute
import reikai.domain.source.SourceKey
import reikai.navigation.NovelWebTarget

/**
 * A source's page in the WebView, opened with that source's own headers. [novelId] is the novel the page
 * belongs to, which a source taking pages reads; a manga page has no such reader.
 */
fun SourceKey.webViewRoute(url: String, title: String?, novelId: Long? = null): WebViewRoute = when (this) {
    is SourceKey.Manga -> WebViewRoute(url = url, initialTitle = title, sourceId = id)
    is SourceKey.Novel -> WebViewRoute(url = url, initialTitle = title, novel = NovelWebTarget.Page(id, novelId))
}

/** A light-novel plugin's site in the WebView, whose storage is kept for the plugin if it asks. */
fun pluginWebViewRoute(pluginId: String, url: String, title: String): WebViewRoute =
    WebViewRoute(url = url, initialTitle = title, novel = NovelWebTarget.Plugin(pluginId))
