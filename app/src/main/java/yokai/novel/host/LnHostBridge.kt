package yokai.novel.host

import android.content.Context
import android.util.Base64
import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val LN_HOST_TAG = "LnHost"

/**
 * Engine-agnostic host services for LN plugins: HTTP via OkHttp, per-plugin storage via
 * SharedPreferences, and logging. [LnPluginHost] binds these to the JS runtime as host functions
 * (`__lnFetch` / `__lnGetStorage` / `__lnSetStorage` / `__lnLog`). Nothing here touches a WebView.
 */
class LnHostBridge(
    private val context: Context,
    private val client: OkHttpClient,
) {

    /** One OkHttp call, response shaped as the runtime's `makeResponse` expects. Blocking; callers
     *  must invoke it off the engine thread (the host wraps it in a Dispatchers.IO async binding). */
    fun runFetch(url: String, optsJson: String): String {
        return try {
            val opts = parseFetchOpts(optsJson)
            val builder = Request.Builder().url(url)
            val method = opts.method?.uppercase() ?: "GET"
            // Binary body (base64) for gRPC-web / protobuf via fetchProto; multipart for FormData
            // posts (matches browsers / lnreader); otherwise the string body.
            val explicitBody = when {
                opts.bodyBase64 != null -> Base64.decode(opts.bodyBase64, Base64.NO_WRAP).toRequestBody()
                opts.multipart != null -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    opts.multipart.forEach { addFormDataPart(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }) }
                }.build()
                else -> opts.body?.takeIf { method != "GET" && method != "HEAD" }?.toRequestBody()
            }
            // OkHttp rejects a null body for POST/PUT/PATCH; lnreader plugins (e.g. Madara's chapter
            // endpoint) issue bodyless POSTs, so send an empty body rather than crash.
            val body = explicitBody
                ?: ByteArray(0).toRequestBody().takeIf { method == "POST" || method == "PUT" || method == "PATCH" }
            builder.method(method, body)
            opts.headers?.forEach { (k, v) -> builder.header(k, v) }
            val res = client.newCall(builder.build()).execute()
            // Final URL after redirects. lnreader's Response shim exposes this as `response.url`;
            // the Madara plugin family compares it to the request host to detect Cloudflare/captcha
            // redirects, and crashes on `undefined.split('/')` if it's missing.
            val finalUrl = res.request.url.toString()
            val headers = res.headers.toMultimap()
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            // Binary responses (protobuf) are returned base64 so they survive the string bridge; the
            // body can be read only once, so the two branches are mutually exclusive.
            val bodyText: String
            val bodyBase64: String?
            if (opts.binary) {
                bodyText = ""
                bodyBase64 = Base64.encodeToString(res.body?.bytes() ?: ByteArray(0), Base64.NO_WRAP)
            } else {
                bodyText = res.body?.string().orEmpty()
                bodyBase64 = null
            }
            JSON.encodeToString(
                FetchResponseDto.serializer(),
                FetchResponseDto(res.code, res.message, headers, bodyText, finalUrl, null, bodyBase64),
            )
        } catch (e: Throwable) {
            Logger.e(LN_HOST_TAG, e) { "fetch($url) failed" }
            JSON.encodeToString(
                FetchResponseDto.serializer(),
                FetchResponseDto(0, "", emptyMap(), "", url, e.message ?: e.javaClass.simpleName, null),
            )
        }
    }

    fun getStorage(pluginId: String, key: String): String? = prefs(pluginId).getString(key, null)

    fun setStorage(pluginId: String, key: String, value: String?) {
        prefs(pluginId).edit().apply {
            if (value == null) remove(key) else putString(key, value)
            apply()
        }
    }

    fun log(level: String, message: String) {
        when (level.lowercase()) {
            "error" -> Logger.e(LN_HOST_TAG) { message }
            "warn" -> Logger.w(LN_HOST_TAG) { message }
            "debug" -> Logger.d(LN_HOST_TAG) { message }
            else -> Logger.i(LN_HOST_TAG) { message }
        }
    }

    private fun prefs(pluginId: String) =
        context.getSharedPreferences("ln_storage_$pluginId", Context.MODE_PRIVATE)

    /** Wipe a plugin's @libs/storage scope. Called on uninstall (so a reinstall doesn't pick up
     *  stale login state) and from the Clear data overflow action. */
    fun clearPluginStorage(pluginId: String) {
        prefs(pluginId).edit().clear().apply()
    }

    /**
     * lnreader's FetchInit allows `headers` as a plain object or Headers instance, `body` as
     * string or FormData. The bridge accepts only the JSON-friendly subset; the runtime serializes
     * FormData to a urlencoded string before it reaches here.
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
        // Field pairs for a multipart/form-data body (set by fetchApi for FormData posts).
        val multipart: List<List<String>>? = null,
        // Set by fetchProto: base64 request body + flag to return the response base64 (binary-safe).
        val bodyBase64: String? = null,
        val binary: Boolean = false,
    )

    @Serializable
    private data class FetchResponseDto(
        val status: Int,
        val statusText: String,
        val headers: Map<String, String>,
        val body: String,
        val url: String,
        val error: String?,
        val bodyBase64: String? = null,
    )

    companion object {
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
