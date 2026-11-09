package reikai.presentation.webview

import android.webkit.WebView
import eu.kanade.tachiyomi.util.system.getHtml
import kotlinx.serialization.json.Json

/** An action on the page the in-app browser shows, handed the page's address and markup. */
class WebPageAction(val title: String, val onPage: (url: String, html: String) -> Unit)

/** The markup of the page on screen, or null when there is none to read. */
suspend fun WebView.pageHtml(): String? {
    // The result arrives as a JSON string literal, or "null" with no document.
    val result = getHtml()
    return runCatching { Json.decodeFromString<String>(result) }.getOrNull()
}
