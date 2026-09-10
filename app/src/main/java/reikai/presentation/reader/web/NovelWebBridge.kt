package reikai.presentation.reader.web

import android.webkit.JavascriptInterface

/**
 * What the WebView rendering mode's page can call. Named methods rather than the legacy reader's
 * single `postMessage`, so an argument that is a chapter id stays one instead of being re-parsed
 * out of JSON at both ends.
 *
 * Every method arrives on a WebView binder thread, so a callback that touches the UI marshals.
 * A chapter id crosses as text, because a JS number cannot hold every `Long` exactly.
 */
class NovelWebBridge(
    private val onVisibleChapter: (chapterId: Long) -> Unit,
    private val onProgress: (chapterId: Long, fraction: Double) -> Unit,
    private val onProgressSettled: (chapterId: Long, fraction: Double) -> Unit,
    private val onReachedEnd: (chapterId: Long) -> Unit,
    private val onReachedStart: (chapterId: Long) -> Unit,
    private val onToggleMenu: () -> Unit,
    private val onStepChapter: (forward: Boolean) -> Unit,
    private val onReady: () -> Unit,
) {

    @JavascriptInterface
    fun onVisibleChapter(chapterId: String) {
        chapterId.toLongOrNull()?.let(onVisibleChapter)
    }

    @JavascriptInterface
    fun onProgress(chapterId: String, fraction: Double) {
        chapterId.toLongOrNull()?.let { onProgress(it, fraction) }
    }

    @JavascriptInterface
    fun onProgressSettled(chapterId: String, fraction: Double) {
        chapterId.toLongOrNull()?.let { onProgressSettled(it, fraction) }
    }

    @JavascriptInterface
    fun onReachedEnd(chapterId: String) {
        chapterId.toLongOrNull()?.let(onReachedEnd)
    }

    @JavascriptInterface
    fun onReachedStart(chapterId: String) {
        chapterId.toLongOrNull()?.let(onReachedStart)
    }

    @JavascriptInterface
    fun onToggleMenu() = onToggleMenu.invoke()

    @JavascriptInterface
    fun onStepChapter(forward: Boolean) = onStepChapter.invoke(forward)

    @JavascriptInterface
    fun onReady() = onReady.invoke()

    companion object {
        /** The name the page reaches this by, matching `reader.js`. */
        const val NAME = "ReikaiWeb"
    }
}
