package yokai.novel.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import co.touchlab.kermit.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * Hosts lnreader plugins inside an Android WebView. Construct on the UI thread; the WebView is
 * created off-screen and never attached to a window.
 *
 * Each plugin method call is suspending; success returns a strongly-typed Kotlin value, failure
 * throws [LnPluginException]. A 30-second per-call timeout guards against runaway plugins.
 */
@SuppressLint("SetJavaScriptEnabled")
class LnPluginHost(
    context: Context,
    client: OkHttpClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Use a Looper-backed handler instead of webView.post: the WebView is never attached to a
    // window, so View.post() queues forever.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webView: WebView
    private val bridge: LnHostBridge
    private val pendingCalls = ConcurrentHashMap<String, CancellableContinuation<String>>()
    private val callbackIdCounter = AtomicLong(0)
    private val ready = CompletableDeferred<Unit>()

    init {
        // WebView must be created with an Activity-flavored Context (using applicationContext here
        // silently breaks asset loading; onPageFinished never fires and every call times out).
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Logger.i(LN_HOST_TAG) {
                        "[console:${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Logger.i(LN_HOST_TAG) { "WebView onPageStarted url=$url" }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Logger.i(LN_HOST_TAG) { "WebView onPageFinished url=$url" }
                    if (!ready.isCompleted) ready.complete(Unit)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    Logger.e(LN_HOST_TAG) {
                        "WebView resource error: ${request?.url} -> ${error?.errorCode} ${error?.description}"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    Logger.e(LN_HOST_TAG) {
                        "WebView http error: ${request?.url} -> ${errorResponse?.statusCode}"
                    }
                }
            }
        }
        bridge = LnHostBridge(
            context = context.applicationContext,
            client = client,
            scope = scope,
            evaluateJs = { js -> mainHandler.post { webView.evaluateJavascript(js, null) } },
            onResult = { cbId, json ->
                pendingCalls.remove(cbId)?.takeIf { it.isActive }?.resume(json)
            },
        )
        webView.addJavascriptInterface(bridge, "LnHostBridge")
        webView.loadUrl("file:///android_asset/lnhost/bootstrap.html")
    }

    suspend fun loadPlugin(pluginId: String, source: String, iconUrl: String? = null): LnPluginInfo {
        ready.await()
        val js = "JSON.stringify(window.__lnhost.loadPlugin(" +
            "${jsStr(pluginId)}, ${jsStr(source)}, ${jsStr(iconUrl ?: "")}))"
        val rawJsString = evaluateJsSuspending(js)
        val innerJson = JSON.decodeFromString(String.serializer(), rawJsString)
        return JSON.decodeFromString(LnPluginInfo.serializer(), innerJson)
    }

    suspend fun popularNovels(pluginId: String, pageNo: Int, optionsJson: String = "{}"): List<NovelItem> {
        val args = listOf<JsonElement>(
            JsonPrimitive(pageNo),
            JSON.parseToJsonElement(optionsJson),
        )
        val raw = callMethod(pluginId, "popularNovels", args)
        return JSON.decodeFromJsonElement(ListSerializer(NovelItem.serializer()), raw)
    }

    suspend fun parseNovel(pluginId: String, novelPath: String): SourceNovel {
        val raw = callMethod(pluginId, "parseNovel", listOf(JsonPrimitive(novelPath)))
        return JSON.decodeFromJsonElement(SourceNovel.serializer(), raw)
    }

    suspend fun parseChapter(pluginId: String, chapterPath: String): String {
        val raw = callMethod(pluginId, "parseChapter", listOf(JsonPrimitive(chapterPath)))
        return raw.jsonPrimitive.content
    }

    suspend fun searchNovels(pluginId: String, query: String, pageNo: Int): List<NovelItem> {
        val raw = callMethod(
            pluginId,
            "searchNovels",
            listOf(JsonPrimitive(query), JsonPrimitive(pageNo)),
        )
        return JSON.decodeFromJsonElement(ListSerializer(NovelItem.serializer()), raw)
    }

    fun destroy() {
        scope.cancel()
        mainHandler.post {
            webView.removeJavascriptInterface("LnHostBridge")
            webView.destroy()
        }
    }

    private suspend fun callMethod(
        pluginId: String,
        method: String,
        args: List<JsonElement>,
    ): JsonElement {
        ready.await()
        val callbackId = newCallbackId()
        val argsJson = JSON.encodeToString(ListSerializer(JsonElement.serializer()), args)
        val jsCall = "window.__lnhost.callMethod(" +
            "${jsStr(pluginId)}, ${jsStr(method)}, ${jsStr(argsJson)}, ${jsStr(callbackId)});"
        val resultJson = withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine<String> { cont ->
                pendingCalls[callbackId] = cont
                cont.invokeOnCancellation { pendingCalls.remove(callbackId) }
                mainHandler.post { webView.evaluateJavascript(jsCall, null) }
            }
        }
        val result = JSON.decodeFromString(LnCallResult.serializer(), resultJson)
        if (!result.ok) throw LnPluginException(result.error ?: "$method failed without message")
        return result.value ?: JsonNull
    }

    private suspend fun evaluateJsSuspending(js: String): String =
        withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine<String> { cont ->
                mainHandler.post {
                    webView.evaluateJavascript(js) { result ->
                        if (cont.isActive) cont.resume(result ?: "null")
                    }
                }
            }
        }

    private fun newCallbackId() = "cb_" + callbackIdCounter.incrementAndGet()

    private fun jsStr(s: String): String = JSON.encodeToString(String.serializer(), s)

    companion object {
        private const val TIMEOUT_MS = 30_000L
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}

class LnPluginException(message: String) : Exception(message)
