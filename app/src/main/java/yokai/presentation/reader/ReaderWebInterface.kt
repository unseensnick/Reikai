package yokai.presentation.reader

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Android side of the reader's JS bridge, exposed to the WebView as `window.NativeReader`. The
 * bundled LNReader JS posts JSON messages through `window.ReactNativeWebView.postMessage`, which a
 * shim in [buildReaderHtml] forwards here.
 *
 * Messages run on a WebView background thread, so UI-affecting callbacks must marshal to the main
 * thread at the call site ([onSave] is safe off-thread: it only triggers a DB write; [onNext]/[onPrev]
 * marshal because they drive UI state). The bridge surface is intentionally tiny (parse + dispatch
 * known types); unknown types are ignored. Handles `hide` (toggle chrome), `console` (debug log),
 * `save` (scroll progress), and `next`/`prev` (chapter nav); TTS (Phase 3) is added later.
 */
class ReaderWebInterface(
    private val onHide: () -> Unit,
    private val onConsole: (String) -> Unit,
    private val onSave: (Int) -> Unit,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hide" -> onHide()
            "console" -> onConsole(json.optString("msg"))
            "save" -> json.optInt("data", -1).takeIf { it >= 0 }?.let(onSave)
            "next" -> onNext()
            "prev" -> onPrev()
        }
    }
}
