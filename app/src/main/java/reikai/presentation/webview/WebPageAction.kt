package reikai.presentation.webview

import android.webkit.WebView
import eu.kanade.tachiyomi.util.system.getHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import reikai.util.scriptString

/** An action on the page the in-app browser shows, handed the page's address and markup. */
class WebPageAction(val title: String, val onPage: (url: String, html: String) -> Unit)

/** The markup of the page on screen, or null when there is none to read. */
suspend fun WebView.pageHtml(): String? {
    val result = getHtml()
    return withContext(Dispatchers.Default) { scriptString(result) }
}
