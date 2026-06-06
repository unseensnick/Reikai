package yokai.presentation.reader

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Android side of the reader's JS bridge, exposed to the WebView as `window.NativeReader`. The
 * bundled LNReader JS posts JSON messages through `window.ReactNativeWebView.postMessage`, which a
 * shim in [buildReaderHtml] forwards here.
 *
 * Messages run on a WebView background thread, so UI-affecting callbacks must marshal to the main
 * thread at the call site. The bridge surface is intentionally tiny (parse + dispatch known types);
 * unknown types are ignored. Phase 1.3 handles `hide` (toggle chrome) and `console` (debug log);
 * `save` (1.5), `next`/`prev` (1.6) and TTS (Phase 3) are added as those steps land.
 */
class ReaderWebInterface(
    private val onHide: () -> Unit,
    private val onConsole: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hide" -> onHide()
            "console" -> onConsole(json.optString("msg"))
        }
    }
}
