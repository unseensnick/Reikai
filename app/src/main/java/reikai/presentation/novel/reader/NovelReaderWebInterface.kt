package reikai.presentation.novel.reader

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Android side of the reader's JS bridge, exposed to the WebView as `window.NativeReader`. The
 * vendored `core.js` posts JSON messages through `window.ReactNativeWebView.postMessage`, which a
 * shim in [buildReaderHtml] forwards here.
 *
 * Messages run on a WebView background thread, so UI-affecting callbacks marshal to the main thread
 * at the call site ([onSave] is safe off-thread: it only triggers a DB write). The surface is tiny
 * (parse + dispatch known types); unknown types are ignored. We handle `hide` (toggle the Compose
 * chrome), `save` (scroll progress), and `console` (debug log); prev/next are Compose buttons.
 */
class NovelReaderWebInterface(
    private val onHide: () -> Unit,
    private val onConsole: (String) -> Unit,
    private val onSave: (Int) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hide" -> onHide()
            "console" -> onConsole(json.optString("msg"))
            "save" -> json.optInt("data", -1).takeIf { it >= 0 }?.let(onSave)
        }
    }
}
