package reikai.presentation.novel.reader

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Android side of the reader's JS bridge, exposed to the WebView as `window.NativeReader`. The
 * vendored `core.js` posts JSON messages through `window.ReactNativeWebView.postMessage`, which a
 * shim in [buildReaderHtml] forwards here.
 *
 * Messages run on a WebView background thread, so UI-affecting callbacks marshal to the main thread
 * at the call site ([onSave] is safe off-thread: it only triggers a DB write). The surface is small
 * (parse + dispatch known types); unknown types are ignored. We handle `hide` (toggle the Compose
 * chrome), `save` (scroll progress), `console` (debug log), the `core.js` TTS messages, and the
 * bundled `reikai-ready` ping emitted once a chapter document is up; prev/next are Compose buttons.
 */
class NovelReaderWebInterface(
    private val onHide: () -> Unit,
    private val onConsole: (String) -> Unit,
    private val onSave: (Int) -> Unit,
    private val onTtsMessage: (String, JSONObject) -> Unit,
    private val onReaderReady: () -> Unit,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (val type = json.optString("type")) {
            "hide" -> onHide()
            "console" -> onConsole(json.optString("msg"))
            "save" -> json.optInt("data", -1).takeIf { it >= 0 }?.let(onSave)
            "reikai-ready" -> onReaderReady()
            "speak", "pause-speak", "stop-speak", "tts-queue", "tts-state", "next" ->
                onTtsMessage(type, json)
        }
    }
}
