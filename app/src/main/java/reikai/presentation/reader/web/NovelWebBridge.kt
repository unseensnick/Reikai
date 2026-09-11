package reikai.presentation.reader.web

import android.webkit.JavascriptInterface

/**
 * What the WebView rendering mode's page can call, as named methods so a chapter id stays one rather
 * than being re-parsed out of JSON. A chapter id crosses as text, since a JS number cannot hold every
 * `Long`. Every method carries the document's token: the object is exposed to every frame, and a
 * chapter's script, a frame it creates, or the page being replaced can call it without holding one.
 */
class NovelWebBridge(
    /** Runs [call] if [documentToken] names the document the host built last. Every method arrives on
     *  a WebView binder thread, so this is also where a call reaches the main thread. */
    private val fromDocument: (documentToken: String, call: () -> Unit) -> Unit,
    private val onVisibleChapter: (chapterId: Long) -> Unit,
    private val onProgress: (chapterId: Long, fraction: Double) -> Unit,
    private val onProgressSettled: (chapterId: Long, fraction: Double) -> Unit,
    private val onRetryBoundary: (forward: Boolean) -> Unit,
    private val onToggleMenu: () -> Unit,
    private val onStepChapter: (forward: Boolean) -> Unit,
    private val onChapterFits: (chapterId: Long, fits: Boolean) -> Unit,
    private val onChapterEndSeen: (chapterId: Long) -> Unit,
    /** The one call that is not gated: it is what tells the host which token is the page's. */
    private val onReady: (documentToken: String) -> Unit,
) {

    @JavascriptInterface
    fun onVisibleChapter(documentToken: String, chapterId: String) {
        chapterId.toLongOrNull()?.let { fromDocument(documentToken) { onVisibleChapter(it) } }
    }

    @JavascriptInterface
    fun onProgress(documentToken: String, chapterId: String, fraction: Double) {
        chapterId.toLongOrNull()?.let { fromDocument(documentToken) { onProgress(it, fraction) } }
    }

    @JavascriptInterface
    fun onProgressSettled(documentToken: String, chapterId: String, fraction: Double) {
        chapterId.toLongOrNull()?.let { fromDocument(documentToken) { onProgressSettled(it, fraction) } }
    }

    @JavascriptInterface
    fun onRetryBoundary(documentToken: String, forward: Boolean) =
        fromDocument(documentToken) { onRetryBoundary.invoke(forward) }

    @JavascriptInterface
    fun onToggleMenu(documentToken: String) = fromDocument(documentToken) { onToggleMenu.invoke() }

    @JavascriptInterface
    fun onStepChapter(documentToken: String, forward: Boolean) =
        fromDocument(documentToken) { onStepChapter.invoke(forward) }

    @JavascriptInterface
    fun onChapterFits(documentToken: String, chapterId: String, fits: Boolean) {
        chapterId.toLongOrNull()?.let { fromDocument(documentToken) { onChapterFits(it, fits) } }
    }

    @JavascriptInterface
    fun onChapterEndSeen(documentToken: String, chapterId: String) {
        chapterId.toLongOrNull()?.let { fromDocument(documentToken) { onChapterEndSeen(it) } }
    }

    @JavascriptInterface
    fun onReady(documentToken: String) = onReady.invoke(documentToken)

    companion object {
        /** The name the page reaches this by, matching `reader.js`. */
        const val NAME = "ReikaiWeb"
    }
}
