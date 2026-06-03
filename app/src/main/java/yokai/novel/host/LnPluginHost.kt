package yokai.novel.host

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * Hosts lnreader plugins in a headless QuickJS engine (no WebView, no Activity), so novel sources
 * run the same on a screen or on a background worker. The engine is single-threaded, so every call
 * is serialized through [mutex]; the engine is created lazily on first use.
 *
 * Each plugin method call is suspending: success returns a strongly-typed Kotlin value, failure
 * throws [LnPluginException]. A 30-second per-call timeout guards against runaway plugins.
 */
class LnPluginHost(
    context: Context,
    client: OkHttpClient,
) {

    private val appContext = context.applicationContext
    private val bridge = LnHostBridge(appContext, client)

    // QuickJS is not thread-safe: confine all native calls to one thread and serialize with the mutex.
    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "LnPluginHost") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var qjs: QuickJs? = null

    /** Create the engine and load the vendor bundles + runtime once. Caller must hold [mutex]. */
    private suspend fun engine(): QuickJs {
        qjs?.let { return it }
        val q = QuickJs.create(engineDispatcher)
        q.function("__lnLog") { args ->
            bridge.log(args.getOrNull(0) as? String ?: "info", args.getOrNull(1) as? String ?: "")
            null
        }
        q.function("__lnGetStorage") { args -> bridge.getStorage(args[0] as String, args[1] as String) }
        q.function("__lnSetStorage") { args ->
            bridge.setStorage(args[0] as String, args[1] as String, args.getOrNull(2) as? String)
            null
        }
        q.asyncFunction("__lnFetch") { args ->
            withContext(Dispatchers.IO) {
                bridge.runFetch(args[0] as String, args.getOrNull(1) as? String ?: "{}")
            }
        }
        // protobuf.js (and other UMD bundles) attach to a global they find via `typeof self/window`,
        // which QuickJS lacks; seed them before the vendors load so `globalThis.protobuf` resolves.
        // Wrapped in an IIFE so the completion value is undefined, not globalThis (dokar can't
        // marshal the self-referential global back to Kotlin: "circular reference").
        q.evaluate<Any?>("(function(){globalThis.self=globalThis;globalThis.window=globalThis;})()")
        // Vendor bundles define the cheerio / htmlparser2 / dayjs / protobuf globals the runtime +
        // plugins need (protobuf powers @libs/fetch's fetchProto for gRPC-web sources e.g. WuxiaWorld).
        q.evaluate<Any?>(asset("lnhost/vendor/dayjs.min.js"))
        q.evaluate<Any?>(asset("lnhost/vendor/htmlparser2.min.js"))
        q.evaluate<Any?>(asset("lnhost/vendor/cheerio.min.js"))
        q.evaluate<Any?>(asset("lnhost/vendor/protobuf.min.js"))
        q.evaluate<Any?>(asset("lnhost/headless.js"))
        qjs = q
        return q
    }

    private fun asset(path: String): String =
        appContext.assets.open(path).bufferedReader().use { it.readText() }

    suspend fun loadPlugin(
        pluginId: String,
        source: String,
        iconUrl: String? = null,
        lang: String? = null,
    ): LnPluginInfo = withTimeout(LOAD_TIMEOUT_MS) {
        mutex.withLock {
            val infoJson = engine().evaluate<String>(
                "JSON.stringify(globalThis.__lnLoadPlugin(" +
                    "${jsStr(pluginId)}, ${jsStr(source)}, ${jsStr(iconUrl ?: "")}, ${jsStr(lang ?: "")}))",
            )
            JSON.decodeFromString(LnPluginInfo.serializer(), infoJson)
        }
    }

    suspend fun popularNovels(pluginId: String, pageNo: Int, optionsJson: String = "{}"): List<NovelItem> {
        val args = listOf<JsonElement>(JsonPrimitive(pageNo), JSON.parseToJsonElement(optionsJson))
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

    /** Optional lnreader `resolveUrl`; null when the plugin doesn't define it (most don't) or errors,
     *  so callers fall back to the source site. */
    suspend fun resolveUrl(pluginId: String, path: String, isNovel: Boolean): String? = try {
        val raw = callMethod(pluginId, "resolveUrl", listOf(JsonPrimitive(path), JsonPrimitive(isNovel)))
        raw.jsonPrimitive.contentOrNull
    } catch (e: Exception) {
        null
    }

    suspend fun searchNovels(pluginId: String, query: String, pageNo: Int): List<NovelItem> {
        val raw = callMethod(
            pluginId,
            "searchNovels",
            listOf(JsonPrimitive(query), JsonPrimitive(pageNo)),
        )
        return JSON.decodeFromJsonElement(ListSerializer(NovelItem.serializer()), raw)
    }

    /** Wipe a plugin's @libs/storage scope without unloading it from the host. Used by the
     *  uninstall flow (paired with [LnPluginInstaller.uninstall]) and by the LN overflow's
     *  standalone Clear data action. */
    fun clearPluginStorage(pluginId: String) {
        bridge.clearPluginStorage(pluginId)
    }

    fun destroy() {
        val q = qjs
        qjs = null
        // Close on the engine thread (close() may pump native state), then retire the executor.
        CoroutineScope(SupervisorJob() + engineDispatcher).launch {
            runCatching { q?.close() }
            engineExecutor.shutdown()
        }
    }

    private suspend fun callMethod(
        pluginId: String,
        method: String,
        args: List<JsonElement>,
    ): JsonElement = withTimeout(CALL_TIMEOUT_MS) {
        mutex.withLock {
            val q = engine()
            val argsJson = JSON.encodeToString(ListSerializer(JsonElement.serializer()), args)
            // __lnCallMethod is async (it fetches). evaluate returns the Promise, not its value, so
            // stash the settled result on a global the engine fills while evaluate pumps the job
            // queue (including the suspend __lnFetch binding), then read it back. The mutex makes the
            // shared global safe.
            q.evaluate<Any?>(
                "globalThis.__lnPending='__pending__';" +
                    "globalThis.__lnCallMethod(${jsStr(pluginId)}, ${jsStr(method)}, ${jsStr(argsJson)})" +
                    ".then(function(r){globalThis.__lnPending=r;}," +
                    "function(e){globalThis.__lnPending=JSON.stringify({ok:false,error:String((e&&e.message)||e)});});",
            )
            val resultJson = q.evaluate<String>("String(globalThis.__lnPending)")
            val result = JSON.decodeFromString(LnCallResult.serializer(), resultJson)
            if (!result.ok) throw LnPluginException(result.error ?: "$method failed without message")
            result.value ?: JsonNull
        }
    }

    private fun jsStr(s: String): String = JSON.encodeToString(String.serializer(), s)

    companion object {
        // loadPlugin is CPU-only (evaluate the plugin code); 30s is ample.
        private const val LOAD_TIMEOUT_MS = 30_000L
        // callMethod issues HTTP, which can route through the shared CloudflareInterceptor: a WebView
        // solve (30s latch) and, on failure, a Flaresolverr fallback (90s callTimeout). A 30s budget
        // killed the call right as the WebView gave up, so Flaresolverr never ran (novels failed CF
        // where manga, which has no such wrapper, succeeds). Cover the full WebView + Flaresolverr path.
        private const val CALL_TIMEOUT_MS = 180_000L
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}

class LnPluginException(message: String) : Exception(message)
