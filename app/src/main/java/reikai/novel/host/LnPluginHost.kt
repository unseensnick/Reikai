package reikai.novel.host

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * Hosts lnreader plugins in headless QuickJS engines (no WebView, no Activity), so novel sources
 * run the same on a screen or on a background worker.
 *
 * Isolation is per plugin: each plugin gets its own engine on its own confinement thread, created
 * lazily on first call and closed again after [IDLE_CLOSE_MS] of disuse, so concurrent calls to
 * different sources (global search, browse while the update job runs) no longer serialize behind
 * one app-wide lane, and an idle plugin holds no native engine. [loadPlugin] (info extraction at
 * install / app start) runs on a shared loader engine instead, so a bulk `ensureLoaded` still costs
 * one engine, not one per installed plugin; the load args are retained and replayed into the
 * plugin's own engine on its first real call.
 *
 * Each plugin method call is suspending: success returns a strongly-typed Kotlin value, failure
 * throws [LnPluginException]. Per-call timeouts guard against runaway plugins.
 */
class LnPluginHost(
    context: Context,
    client: OkHttpClient,
    preferenceStore: PreferenceStore,
) {

    private val appContext = context.applicationContext

    // the device's real WebView User-Agent (real model + Android + Chrome version), like LNReader.
    // Mihon's network client otherwise defaults to a stripped generic "Android 10; K" UA that some LN
    // sources answer with a degraded page (e.g. Novel Bin serves 200x89 thumbnail covers to it).
    private val deviceUserAgent: String =
        runCatching { android.webkit.WebSettings.getDefaultUserAgent(appContext) }.getOrDefault("")
    private val bridge = LnHostBridge(preferenceStore, client, deviceUserAgent)

    /** What it takes to (re)load a plugin into an engine; retained per plugin so its own engine can
     *  replay the load after lazy creation or an idle close. */
    private class LoadArgs(
        val scopeId: String,
        val source: String,
        val iconUrl: String?,
        val lang: String?,
    )

    /**
     * One QuickJS engine plus its confinement thread. QuickJS is not thread-safe: all native calls
     * for a slot are confined to its single-thread executor and serialized through its [mutex].
     * All fields except [lastUsedMs] are only touched while holding [mutex].
     */
    private class EngineSlot(val label: String) {
        val mutex = Mutex()
        var executor: ExecutorService? = null
        var dispatcher: CoroutineContext? = null
        var qjs: QuickJs? = null
        var callSeq = 0L
        var loadArgs: LoadArgs? = null

        @Volatile
        var lastUsedMs = 0L
    }

    private val loaderSlot = EngineSlot("loader")
    private val pluginSlots = ConcurrentHashMap<String, EngineSlot>()

    // Idle sweeper lifecycle; guarded by [sweeperLock].
    private val sweeperLock = Any()
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sweeperJob: Job? = null

    // Vendor bundles + runtime, read from assets once and evaluated into every engine, in order.
    // headless.js last: it wires the shims over the vendor globals.
    private val runtimeScripts: List<String> by lazy {
        listOf(
            "lnhost/vendor/dayjs.min.js",
            "lnhost/vendor/htmlparser2.min.js",
            "lnhost/vendor/cheerio.min.js",
            // protobuf powers @libs/fetch's fetchProto for gRPC-web sources e.g. WuxiaWorld.
            "lnhost/vendor/protobuf.min.js",
            // @noble/ciphers AES-GCM, backs @libs/aes (wtrlab decrypts chapter bodies with it).
            "lnhost/vendor/noble-ciphers.min.js",
            "lnhost/headless.js",
        ).map(::asset)
    }

    /** Create the slot's engine, load the runtime, and replay the plugin load if the slot has one.
     *  Caller must hold the slot's mutex. */
    private suspend fun EngineSlot.engine(): QuickJs {
        qjs?.let { return it }
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "LnPlugin-$label") }
        val disp = exec.asCoroutineDispatcher()
        val q = QuickJs.create(disp)
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
        // Backs the setTimeout shim in headless.js: plugins use it for rate-limit politeness sleeps and
        // retry backoffs, which QuickJS can't honor natively (no timers). Suspends the engine job pump
        // for the real delay, capped so a buggy/hostile plugin can't hold the slot indefinitely.
        q.asyncFunction("__lnDelay") { args ->
            delay(((args.getOrNull(0) as? Number)?.toLong() ?: 0L).coerceIn(0L, MAX_TIMER_DELAY_MS))
            null
        }
        // protobuf.js (and other UMD bundles) attach to a global they find via `typeof self/window`,
        // which QuickJS lacks; seed them before the vendors load so `globalThis.protobuf` resolves.
        // Wrapped in an IIFE so the completion value is undefined, not globalThis (dokar can't
        // marshal the self-referential global back to Kotlin: "circular reference").
        q.evaluate<Any?>("(function(){globalThis.self=globalThis;globalThis.window=globalThis;})()")
        runtimeScripts.forEach { q.evaluate<Any?>(it) }
        executor = exec
        dispatcher = disp
        qjs = q
        loadArgs?.let { args ->
            // void: the returned info object was already decoded at loadPlugin time; marshalling it
            // back through dokar here would be wasted work (and objects don't cross the bridge).
            q.evaluate<Any?>(
                "void globalThis.__lnLoadPlugin(" +
                    "${jsStr(args.scopeId)}, ${jsStr(args.source)}, " +
                    "${jsStr(args.iconUrl ?: "")}, ${jsStr(args.lang ?: "")})",
            )
        }
        lastUsedMs = System.currentTimeMillis()
        ensureSweeper()
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
        val info = loaderSlot.mutex.withLock {
            val infoJson = loaderSlot.engine().evaluate<String>(
                "JSON.stringify(globalThis.__lnLoadPlugin(" +
                    "${jsStr(pluginId)}, ${jsStr(source)}, ${jsStr(iconUrl ?: "")}, ${jsStr(lang ?: "")}))",
            )
            loaderSlot.lastUsedMs = System.currentTimeMillis()
            JSON.decodeFromString(LnPluginInfo.serializer(), infoJson)
        }
        // Retain the args under the plugin's CANONICAL id (callMethod is keyed by it, which can
        // differ from the URL-derived pluginId), and drop a live engine still running the previous
        // code so the next call re-loads fresh.
        val slot = pluginSlots.getOrPut(info.id) { EngineSlot(info.id) }
        slot.mutex.withLock {
            slot.loadArgs = LoadArgs(pluginId, source, iconUrl, lang)
            closeLocked(slot)
        }
        info
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

    /** Optional lnreader `parsePage`: one page of a paged source's chapter list. Null when the plugin
     *  doesn't define it (single-page source) or it errors, mirroring [resolveUrl]. The plugin returns
     *  only `{ chapters }`, so the path comes from the caller. */
    suspend fun parsePage(pluginId: String, novelPath: String, page: String): SourceNovel? = try {
        val raw = callMethod(pluginId, "parsePage", listOf(JsonPrimitive(novelPath), JsonPrimitive(page)))
        val parsed = JSON.decodeFromJsonElement(SourcePage.serializer(), raw)
        SourceNovel(path = novelPath, chapters = parsed.chapters)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A thrown error here reads to the caller as "no more pages" and silently truncates the
        // chapter list, so at least leave a trace instead of swallowing it blind.
        logcat(LogPriority.DEBUG, e) { "parsePage failed or unsupported for $pluginId" }
        null
    }

    /** Optional lnreader `resolveUrl`; null when the plugin doesn't define it (most don't) or errors,
     *  so callers fall back to the source site. */
    suspend fun resolveUrl(pluginId: String, path: String, isNovel: Boolean): String? = try {
        val raw = callMethod(pluginId, "resolveUrl", listOf(JsonPrimitive(path), JsonPrimitive(isNovel)))
        raw.jsonPrimitive.contentOrNull
    } catch (e: CancellationException) {
        throw e
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
     *  uninstall flow and by a standalone Clear data action. */
    fun clearPluginStorage(pluginId: String) {
        bridge.clearPluginStorage(pluginId)
    }

    /** Per-plugin settings live in the same `storage:` scope a plugin reads via `@libs/storage`, in
     *  lnreader's StoredItem envelope (`{value: ...}`), so a value the settings UI writes here is
     *  exactly what the plugin sees at runtime. Values are typed JsonElements (string / bool / array). */
    fun getSetting(pluginId: String, key: String): JsonElement? {
        val raw = bridge.getStorage(pluginId, "storage:$key") ?: return null
        return runCatching { JSON.parseToJsonElement(raw).jsonObject["value"] }.getOrNull()
    }

    fun setSetting(pluginId: String, key: String, value: JsonElement?) {
        if (value == null) {
            bridge.setStorage(pluginId, "storage:$key", null)
        } else {
            bridge.setStorage(pluginId, "storage:$key", buildJsonObject { put("value", value) }.toString())
        }
    }

    fun destroy() {
        synchronized(sweeperLock) {
            sweeperJob?.cancel()
            sweeperJob = null
        }
        val slots = listOf(loaderSlot) + pluginSlots.values
        pluginSlots.clear()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            slots.forEach { slot -> slot.mutex.withLock { closeLocked(slot) } }
        }
    }

    /** Close a slot's engine and retire its thread. Caller must hold the slot's mutex. */
    private suspend fun closeLocked(slot: EngineSlot) {
        val q = slot.qjs ?: return
        slot.qjs = null
        // Close on the engine thread (close() may pump native state), then retire the executor.
        slot.dispatcher?.let { disp -> withContext(disp) { runCatching { q.close() } } }
        slot.executor?.shutdown()
        slot.executor = null
        slot.dispatcher = null
    }

    /** Closes engines nobody has used for [IDLE_CLOSE_MS], so an idle plugin (or the loader after a
     *  startup burst) holds no native engine or thread. Exits when nothing is live; engine creation
     *  restarts it. */
    private fun ensureSweeper() {
        synchronized(sweeperLock) {
            if (sweeperJob?.isActive == true) return
            sweeperJob = hostScope.launch {
                while (true) {
                    delay(SWEEP_INTERVAL_MS)
                    var anyLive = false
                    for (slot in pluginSlots.values + loaderSlot) {
                        if (slot.qjs == null) continue
                        val idle = System.currentTimeMillis() - slot.lastUsedMs >= IDLE_CLOSE_MS
                        // tryLock: a slot mid-call is busy, not idle; skip instead of queueing
                        // behind a call that can legitimately run for minutes.
                        if (!slot.mutex.tryLock()) {
                            anyLive = true
                            continue
                        }
                        try {
                            if (slot.qjs != null && idle) closeLocked(slot) else anyLive = anyLive || slot.qjs != null
                        } finally {
                            slot.mutex.unlock()
                        }
                    }
                    if (!anyLive) {
                        synchronized(sweeperLock) { sweeperJob = null }
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun callMethod(
        pluginId: String,
        method: String,
        args: List<JsonElement>,
    ): JsonElement = withTimeout(CALL_TIMEOUT_MS) {
        val slot = pluginSlots[pluginId] ?: throw LnPluginException("plugin not loaded: $pluginId")
        slot.mutex.withLock {
            val q = slot.engine()
            val argsJson = JSON.encodeToString(ListSerializer(JsonElement.serializer()), args)
            // __lnCallMethod is async (it fetches). evaluate returns the Promise, not its value, so the
            // settled result is parked on a global that the engine fills while evaluate pumps the job
            // queue (including the suspend __lnFetch binding), then read back.
            //
            // Each call parks in its OWN slot, keyed by a call id. A single shared slot was not safe
            // despite the mutex: a promise outlives the call that created it, so a slow call that gave
            // up could settle later and land in the slot the NEXT call then read, handing one novel the
            // parsed metadata of another and writing it over that novel's row. Keying by call id makes
            // a late settle land somewhere nobody reads.
            val callId = "c${++slot.callSeq}"
            q.evaluate<Any?>(
                "globalThis.__lnResults=globalThis.__lnResults||{};" +
                    // A call that timed out never collects its slot; bound the leak.
                    "if(Object.keys(globalThis.__lnResults).length>$MAX_PENDING_SLOTS)" +
                    "globalThis.__lnResults={};" +
                    "globalThis.__lnCallMethod(${jsStr(pluginId)}, ${jsStr(method)}, ${jsStr(argsJson)})" +
                    ".then(function(r){globalThis.__lnResults[${jsStr(callId)}]=r;}," +
                    "function(e){globalThis.__lnResults[${jsStr(callId)}]=" +
                    "JSON.stringify({ok:false,error:String((e&&e.message)||e)});});",
            )
            // Read this call's slot, and keep pumping the job queue until it settles. Reading once and
            // trusting whatever was there is what allowed a stale result to be taken as the answer;
            // waiting turns a not-yet-settled call into the enclosing timeout instead of wrong data.
            val read = "(function(){var v=globalThis.__lnResults[${jsStr(callId)}];" +
                "return v===undefined?$PENDING_JS:String(v);})()"
            var resultJson = q.evaluate<String>(read)
            while (resultJson == PENDING) {
                delay(CALL_POLL_MS)
                resultJson = q.evaluate<String>(read)
            }
            q.evaluate<Any?>("delete globalThis.__lnResults[${jsStr(callId)}];")
            slot.lastUsedMs = System.currentTimeMillis()
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
        // killed the call right as the WebView gave up, so Flaresolverr never ran. Cover the full
        // WebView + Flaresolverr path.
        private const val CALL_TIMEOUT_MS = 180_000L

        // Ceiling for a plugin setTimeout / retry-backoff sleep (see __lnDelay). Honors real Retry-After
        // backoffs (usually 5-30s) while staying well under CALL_TIMEOUT_MS, which remains the backstop
        // against a plugin that asks to sleep far longer.
        private const val MAX_TIMER_DELAY_MS = 30_000L

        // Sentinel for "this call's slot is still empty". Compared in Kotlin and produced by the JS
        // read, so the two spellings have to agree.
        private const val PENDING = "__pending__"
        private const val PENDING_JS = "'$PENDING'"

        // How long to wait between pumps while a call settles. The first evaluate usually drives the
        // promise to completion, so this only costs anything on a genuinely slow fetch.
        private const val CALL_POLL_MS = 25L

        // Slots are collected by the call that made them, so this only grows when a call times out and
        // never comes back for its result. Well above any realistic in-flight count.
        private const val MAX_PENDING_SLOTS = 64

        // An engine unused this long is closed (native engine + thread reclaimed); the next call
        // re-creates it and replays the plugin load. Mirrors tsundoku's 60s instance timeout.
        private const val IDLE_CLOSE_MS = 60_000L
        private const val SWEEP_INTERVAL_MS = 30_000L

        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}

class LnPluginException(message: String) : Exception(message)
