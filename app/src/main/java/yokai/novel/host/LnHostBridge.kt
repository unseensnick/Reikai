package yokai.novel.host

import android.content.Context
import android.webkit.JavascriptInterface
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val LN_HOST_TAG = "LnHost"

/**
 * @JavascriptInterface surface exposed to the WebView. Methods are invoked from bootstrap.js.
 * [evaluateJs] posts arbitrary JS back to the WebView main thread.
 * [onResult] hands a finished plugin-method JSON back to LnPluginHost so it can resume the
 * awaiting Kotlin coroutine.
 */
class LnHostBridge(
    private val context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val evaluateJs: (String) -> Unit,
    private val onResult: (callbackId: String, json: String) -> Unit,
) {

    @JavascriptInterface
    fun fetch(url: String, optsJson: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            val responseJson = runFetch(url, optsJson)
            withContext(Dispatchers.Main) {
                evaluateJs(
                    "window.__lnhost.resolveFetch(" +
                        JSON.encodeToString(String.serializer(), callbackId) + ", " +
                        JSON.encodeToString(String.serializer(), responseJson) + ");",
                )
            }
        }
    }

    @JavascriptInterface
    fun resolveResult(callbackId: String, json: String) {
        onResult(callbackId, json)
    }

    @JavascriptInterface
    fun getStorage(pluginId: String, key: String): String? {
        return prefs(pluginId).getString(key, null)
    }

    @JavascriptInterface
    fun setStorage(pluginId: String, key: String, value: String?) {
        prefs(pluginId).edit().apply {
            if (value == null) remove(key) else putString(key, value)
            apply()
        }
    }

    @JavascriptInterface
    fun log(level: String, message: String) {
        when (level.lowercase()) {
            "error" -> Logger.e(LN_HOST_TAG) { message }
            "warn" -> Logger.w(LN_HOST_TAG) { message }
            "debug" -> Logger.d(LN_HOST_TAG) { message }
            else -> Logger.i(LN_HOST_TAG) { message }
        }
    }

    private fun runFetch(url: String, optsJson: String): String {
        return try {
            val opts = parseFetchOpts(optsJson)
            val builder = Request.Builder().url(url)
            val method = opts.method?.uppercase() ?: "GET"
            val body = opts.body?.takeIf { method != "GET" && method != "HEAD" }?.toRequestBody()
            builder.method(method, body)
            opts.headers?.forEach { (k, v) -> builder.header(k, v) }
            val res = client.newCall(builder.build()).execute()
            val bodyText = res.body?.string().orEmpty()
            val headers = res.headers.toMultimap()
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            JSON.encodeToString(
                FetchResponseDto.serializer(),
                FetchResponseDto(res.code, res.message, headers, bodyText, null),
            )
        } catch (e: Throwable) {
            Logger.e(LN_HOST_TAG, e) { "fetch($url) failed" }
            JSON.encodeToString(
                FetchResponseDto.serializer(),
                FetchResponseDto(0, "", emptyMap(), "", e.message ?: e.javaClass.simpleName),
            )
        }
    }

    private fun prefs(pluginId: String) =
        context.getSharedPreferences("ln_storage_$pluginId", Context.MODE_PRIVATE)

    /** Wipe a plugin's @libs/storage scope. Called on uninstall (so a reinstall doesn't pick
     *  up stale login state) and from the Clear data overflow action. */
    fun clearPluginStorage(pluginId: String) {
        prefs(pluginId).edit().clear().apply()
    }

    /**
     * lnreader's FetchInit allows `headers` as a plain object or Headers instance, `body` as
     * string or FormData. The bridge accepts only the JSON-friendly subset; plugins that serialize
     * FormData would need to stringify it themselves (none of the smoke-test sources do).
     */
    private fun parseFetchOpts(optsJson: String): FetchOpts {
        if (optsJson.isBlank() || optsJson == "{}") return FetchOpts()
        return try {
            JSON.decodeFromString(FetchOpts.serializer(), optsJson)
        } catch (_: Throwable) {
            // Fallback: pick out fields manually so a malformed `body` doesn't abort the whole call.
            val obj = JSON.parseToJsonElement(optsJson).jsonObject
            FetchOpts(
                method = obj["method"]?.jsonPrimitive?.contentOrNull,
                headers = (obj["headers"] as? JsonObject)
                    ?.mapValues { it.value.jsonPrimitive.content },
                body = obj["body"]?.let {
                    if (it is JsonPrimitive) it.content else it.toString()
                },
            )
        }
    }

    @Serializable
    private data class FetchOpts(
        val method: String? = null,
        val headers: Map<String, String>? = null,
        val body: String? = null,
    )

    @Serializable
    private data class FetchResponseDto(
        val status: Int,
        val statusText: String,
        val headers: Map<String, String>,
        val body: String,
        val error: String?,
    )

    companion object {
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
