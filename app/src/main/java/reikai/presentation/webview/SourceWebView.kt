package reikai.presentation.webview

import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import reikai.domain.source.SourceKey

/**
 * A source's page in the WebView, opened with that source's own headers. [novelId] is the novel the page
 * belongs to, which a source taking pages reads; a manga page has no such reader.
 */
fun SourceKey.toWebViewScreen(url: String, title: String?, novelId: Long? = null): WebViewScreen = when (this) {
    is SourceKey.Manga -> WebViewScreen(url = url, initialTitle = title, sourceId = id)
    is SourceKey.Novel -> WebViewScreen(url = url, initialTitle = title, novelId = novelId, novelSourceId = id)
}
