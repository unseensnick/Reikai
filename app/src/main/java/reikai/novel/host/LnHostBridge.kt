package reikai.novel.host

import android.util.Base64
import eu.kanade.tachiyomi.network.WithPluginCookies
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import reikai.novel.network.applyNovelDefaults
import reikai.novel.source.httpFailure
import reikai.novel.source.lnStorageScope
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * Engine-agnostic host services for LN plugins: HTTP over OkHttp, per-plugin storage over the shared
 * [PreferenceStore], and logging. [LnPluginHost] binds these to the JS runtime as host functions
 * (`__lnFetch` / `__lnGetStorage` / `__lnSetStorage` / `__lnLog`); nothing here touches a WebView.
 * Storage is namespaced keys rather than raw SharedPreferences, which the project forbids, keyed
 * `ln_storage::<pluginId>::<key>`.
 */
class LnHostBridge(
    private val preferenceStore: PreferenceStore,
    private val client: OkHttpClient,
    // The device's real WebView User-Agent, resolved per request rather than passed in: reading it
    // loads the WebView provider, which blocks for seconds on a cold device. Doing that while
    // constructing froze the UI, because Metro holds this object's singleton lock across the call.
    private val userAgent: () -> String = { "" },
) {

    /** One OkHttp call, response shaped as the runtime's `makeResponse` expects. Blocking; callers
     *  must invoke it off the engine thread (the host wraps it in a Dispatchers.IO async binding). */
    fun runFetch(url: String, optsJson: String, onFailure: (Throwable) -> Unit): String {
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
            // Default the device WebView UA unless the plugin set one; the Referer is left to the plugin.
            val pluginSetUa = opts.headers?.keys?.any { it.equals("User-Agent", ignoreCase = true) } == true
            builder.applyNovelDefaults(userAgent(), pluginSetUserAgent = pluginSetUa)
            // Laravel / Inertia sources expect the XSRF-TOKEN cookie echoed back as the X-XSRF-TOKEN
            // header. Derive it from the shared cookie jar unless the plugin set the header itself. The
            // token is a per-session CSRF value, applied but never logged.
            val pluginSetXsrf = opts.headers?.keys?.any { it.equals("X-XSRF-TOKEN", ignoreCase = true) } == true
            if (!pluginSetXsrf) {
                url.toHttpUrlOrNull()?.let { httpUrl ->
                    client.cookieJar.loadForRequest(httpUrl)
                        .firstOrNull { it.name == "XSRF-TOKEN" }
                        ?.let { builder.header("X-XSRF-TOKEN", URLDecoder.decode(it.value, "UTF-8")) }
                }
            }
            val pluginCookie = opts.headers?.entries?.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value
            val callClient =
                pluginCookie?.let { client.newBuilder().cookieJar(WithPluginCookies(client.cookieJar, it)).build() }
                    ?: client
            val res = callClient.newCall(builder.build()).execute()
            // A browser's fetch resolves on an error status too, so the plugin gets the page as it is.
            httpFailure(res.code)?.let(onFailure)
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
                bodyBase64 = Base64.encodeToString(res.body.bytes(), Base64.NO_WRAP)
            } else {
                // LNReader's fetchText decodes with the encoding the plugin names over the one the site
                // declares, which for a GBK site labelled gb2312 loses every character outside GB2312.
                val charset = opts.encoding?.let { runCatching { Charset.forName(it) }.getOrNull() }
                bodyText = charset?.let { res.body.bytes().toString(it) } ?: res.body.string()
                bodyBase64 = null
            }
            LnPluginHost.JSON.encodeToString(
                FetchResponseDto.serializer(),
                FetchResponseDto(res.code, res.message, headers, bodyText, finalUrl, null, bodyBase64),
            )
        } catch (e: Throwable) {
            // Without the query or fragment: a search's terms or a plugin's token would reach shared crash logs.
            logcat(LogPriority.ERROR, e) { "fetch(${loggableUrl(url)}) failed" }
            onFailure(e)
            LnPluginHost.JSON.encodeToString(
                FetchResponseDto.serializer(),
                FetchResponseDto(0, "", emptyMap(), "", url, e.message ?: e.javaClass.simpleName, null),
            )
        }
    }

    fun getStorage(pluginId: String, key: String): String? {
        val pref = preferenceStore.getString(scopedKey(pluginId, key))
        return if (pref.isSet()) pref.get() else null
    }

    fun setStorage(pluginId: String, key: String, value: String?) {
        val pref = preferenceStore.getString(scopedKey(pluginId, key))
        if (value == null) pref.delete() else pref.set(value)
    }

    /** Everything [pluginId] has stored, in a stable order, so a change to any of it can be told apart. */
    fun storageSnapshot(pluginId: String): String {
        val scope = lnStorageScope(pluginId)
        return preferenceStore.getAll().filterKeys { it.startsWith(scope) }.toSortedMap()
            .entries.joinToString("\u0000") { (key, value) -> "$key=$value" }
    }

    fun log(level: String, message: String) {
        when (level.lowercase()) {
            "error" -> logcat(LogPriority.ERROR) { message }
            "warn" -> logcat(LogPriority.WARN) { message }
            "debug" -> logcat(LogPriority.DEBUG) { message }
            else -> logcat(LogPriority.INFO) { message }
        }
    }

    private fun scopedKey(pluginId: String, key: String): String = "${lnStorageScope(pluginId)}$key"

    /**
     * lnreader's FetchInit allows `headers` as a plain object or Headers instance, `body` as string or
     * FormData; the runtime flattens a Headers instance and sends FormData as `multipart` field pairs.
     * Each field is read on its own, so one off-shape field (an array for headers, an object for a body)
     * drops alone rather than the multipart or binary body beside it.
     */
    internal fun parseFetchOpts(optsJson: String): FetchOpts {
        if (optsJson.isBlank() || optsJson == "{}") return FetchOpts()
        val obj = LnPluginHost.JSON.parseToJsonElement(optsJson).jsonObject
        fun <T> field(name: String, serializer: KSerializer<T>): T? =
            obj[name]?.let { runCatching { LnPluginHost.JSON.decodeFromJsonElement(serializer, it) }.getOrNull() }
        return FetchOpts(
            method = field("method", String.serializer()),
            // A browser sends a null header value as the text "null", as `content` reads it.
            headers = (obj["headers"] as? JsonObject)?.mapValues {
                (it.value as? JsonPrimitive)?.content
                    ?: it.value.toString()
            },
            body = obj["body"]?.takeIf {
                it !is JsonNull
            }?.let { if (it is JsonPrimitive) it.content else it.toString() },
            multipart = field("multipart", ListSerializer(ListSerializer(String.serializer()))),
            bodyBase64 = field("bodyBase64", String.serializer()),
            binary = field("binary", Boolean.serializer()) ?: false,
            encoding = field("encoding", String.serializer()),
        )
    }

    internal data class FetchOpts(
        val method: String? = null,
        val headers: Map<String, String>? = null,
        val body: String? = null,
        // Field pairs for a multipart/form-data body (set by fetchApi for FormData posts).
        val multipart: List<List<String>>? = null,
        // Set by fetchProto: base64 request body + flag to return the response base64 (binary-safe).
        val bodyBase64: String? = null,
        val binary: Boolean = false,
        // fetchText's charset override, a Java charset name ("gbk").
        val encoding: String? = null,
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
}

internal fun loggableUrl(url: String): String =
    url.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()
        ?: url.substringBefore('?').substringBefore('#')
