package reikai.presentation.webview

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/** An action on the page the in-app browser shows, handed the page's address and markup. */
class WebPageAction(val title: String, val onPage: (url: String, html: String) -> Unit)

/** The markup of the page on screen, or null when there is none to read. */
suspend fun WebView.pageHtml(): String? = suspendCancellableCoroutine { continuation ->
    // The result arrives as a JSON string literal, or "null" with no document.
    evaluateJavascript("document.documentElement.outerHTML") { result ->
        continuation.resume(runCatching { Json.decodeFromString<String>(result) }.getOrNull())
    }
}
