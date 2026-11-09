package reikai.novel.host

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import mihon.core.metro.AppCoroutineScope
import reikai.novel.network.deviceWebViewUserAgent
import reikai.novel.source.FetchFailures
import reikai.novel.source.WEB_STORAGE_KEY_PREFIX
import reikai.quickjs.createQuickJs
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Hosts lnreader plugins in headless QuickJS engines (no WebView, no Activity), so novel sources run the same on a
 * screen or on a background worker. Isolation is per plugin: each gets its own engine on its own confinement thread,
 * created lazily and closed after [IDLE_CLOSE_MS] idle, with its storage bound to its id on the Kotlin side.
 * [inspectPlugin] reads a script in a throwaway engine of its own, so one script cannot leave code behind for the
 * next; [retainPlugin] makes it the plugin's script, replayed into its own engine on the next call. Calls suspend
 * and throw [LnPluginException] on failure, under per-call timeouts.
 */
@Inject
@SingleIn(AppScope::class)
class LnPluginHost(
    @AppCoroutineScope private val scope: CoroutineScope,
    context: Context,
    networkHelper: NetworkHelper,
    preferenceStore: PreferenceStore,
) {

    private val appContext = context.applicationContext

    // The shared helper caches the UA per process and resolves it on the first fetch, which already
    // runs on IO. Reading it here instead would block on WebView load while this object's Metro
    // singleton lock is held, freezing any screen that resolves the same lock.
    private val bridge = LnHostBridge(preferenceStore, networkHelper.client) {
        deviceWebViewUserAgent(appContext)
    }

    /**
     * One QuickJS engine plus its confinement thread. QuickJS is not thread-safe: all native calls
     * for a slot are confined to its single-thread executor and serialized through its [mutex].
     * All fields except the volatile ones are only touched while holding [mutex].
     */
    private class EngineSlot(val label: String, val storage: EngineStorage) {
        val mutex = Mutex()
        var executor: ExecutorService? = null
        var dispatcher: CoroutineDispatcher? = null
        var qjs: QuickJs? = null
        var callSeq = 0L

        // The plugin's script, replayed into each engine this slot builds. [retainPlugin] writes it without
        // the mutex and sets [rebuild] after it, so the next call starts from this script.
        @Volatile
        var source: String? = null

        // The last fetch this slot's engine saw fail, a status of 400 or more included. JS receives only its
        // message, so a failed call re-attaches it as the cause, which is how an error message tells offline
        // from a plugin bug, and a call that answers hands it to its caller's FetchFailures. A new holder per
        // engine, so a fetch a closed engine left running cannot write into the next engine's call.
        var fetchFailure = AtomicReference<Throwable?>(null)

        @Volatile
        var lastUsedMs = 0L

        // Set when the plugin's script or stored state changed under a live engine; the next call starts a fresh one.
        @Volatile
        var rebuild = false

        /** Runs [block] on this slot's thread, starting it if an idle close retired it. dokar evaluates on
         *  the calling thread, so without this a screen's Main ran the plugin. Caller must hold [mutex]. */
        suspend fun <T> onThread(block: suspend () -> T): T {
            val disp = dispatcher ?: Executors.newSingleThreadExecutor { r -> Thread(r, "LnPlugin-$label") }
                .also { executor = it }
                .asCoroutineDispatcher()
                .also { dispatcher = it }
            return withContext(disp) { block() }
        }
    }

    private val pluginSlots = ConcurrentHashMap<String, EngineSlot>()

    // Bounds the throwaway engines a bulk load builds at once.
    private val inspectPermits = Semaphore(INSPECT_PARALLELISM)

    // Idle sweeper lifecycle; guarded by [sweeperLock].
    private val sweeperLock = Any()
    private var sweeperJob: Job? = null

    // Read from assets once and evaluated into every engine, in order.
    private val runtimeScripts: List<String> by lazy { RUNTIME_ASSETS.map(::asset) }

    /** Create the slot's engine, load the runtime, and replay the plugin load if the slot has one. The
     *  engine is kept only once all of that succeeded: a half-built one answered "plugin not loaded" to
     *  every call until it idled out. Caller must hold the slot's mutex and be inside [EngineSlot.onThread]. */
    private suspend fun EngineSlot.engine(): QuickJs {
        qjs?.let { return it }
        val scripts = runtimeScripts
        // A slot's own mutex cannot cover the native init; only creation is serialised, so searches fan out.
        val q = createQuickJs(checkNotNull(dispatcher))
        fetchFailure = AtomicReference(null)
        try {
            setUp(q, scripts)
        } catch (e: Throwable) {
            // On the slot's thread already, so this retires the engine as closeLocked does.
            runCatching { q.close() }
            executor?.shutdown()
            executor = null
            dispatcher = null
            if (e is CancellationException) throw e
            throw LnPluginException("could not start $label: ${e.message}", e)
        }
        qjs = q
        lastUsedMs = System.currentTimeMillis()
        ensureSweeper()
        return q
    }

    private suspend fun EngineSlot.setUp(q: QuickJs, scripts: List<String>) {
        q.function("__lnLog") { args ->
            bridge.log(args.getOrNull(0) as? String ?: "info", args.getOrNull(1) as? String ?: "")
            null
        }
        // Scoped by the slot, never by an id from JS: plugin code can call into the runtime with any id it likes.
        q.function("__lnGetStorage") { args -> storage.get(args[0] as String) }
        q.function("__lnSetStorage") { args ->
            storage.set(args[0] as String, args.getOrNull(1) as? String)
            null
        }
        val failure = fetchFailure
        q.asyncFunction("__lnFetch") { args ->
            withIOContext {
                bridge.runFetch(args[0] as String, args.getOrNull(1) as? String ?: "{}") { failure.set(it) }
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
        scripts.forEach { q.evaluate<Any?>(it) }
        source?.let { script ->
            // void: the returned info object was already decoded when the script was inspected; marshalling
            // it back through dokar here would be wasted work (and objects don't cross the bridge).
            q.evaluate<Any?>("void globalThis.__lnLoadPlugin(${jsStr(script)})")
        }
    }

    private fun asset(path: String): String =
        appContext.assets.open(path).bufferedReader().use { it.readText() }

    /**
     * What [source] says about itself, read in a throwaway engine, so nothing it does there outlives the read.
     * [expectedId] refuses a script declaring another id before its code meets real storage. Only a script
     * declaring [trustedId], the id its URL is installed as, reads that plugin's storage while it is read,
     * which a plugin taking its site from a setting needs; any other script reads none.
     */
    suspend fun inspectPlugin(
        source: String,
        iconUrl: String? = null,
        lang: String? = null,
        expectedId: String? = null,
        trustedId: String? = expectedId,
    ): LnPluginInfo = withPluginTimeout(LOAD_TIMEOUT_MS, "inspectPlugin") {
        val info = inspectPermits.withPermit { readInfo(source, expectedId, trustedId) }
        if (expectedId != null && info.id != expectedId) {
            throw LnPluginException("now declares the id ${info.id}, installed as $expectedId")
        }
        info.copy(
            iconUrl = iconUrl?.ifEmpty { null },
            lang = lang?.ifEmpty { null } ?: info.lang,
            supportsLatest = derivesLatestSupport(source),
        )
    }

    /**
     * Makes [source] the script of the plugin [pluginId], loaded by its next call in a fresh engine bound to
     * that id. Takes no lock and does no I/O, so an installer can call it inside its own registry section.
     */
    fun retainPlugin(pluginId: String, source: String) {
        val slot = pluginSlots.getOrPut(pluginId) { EngineSlot(pluginId, EngineStorage(bridge, pluginId)) }
        slot.source = source
        slot.rebuild = true
    }

    private suspend fun readInfo(source: String, expectedId: String?, trustedId: String?): LnPluginInfo {
        val slot = EngineSlot("loader", EngineStorage(bridge, null))
        return slot.mutex.withLock {
            try {
                slot.onThread {
                    val q = slot.engine()
                    val declared = q.evaluate<String?>("globalThis.__lnDiscoverId(${jsStr(source)})")
                    if (expectedId != null && declared != null && declared != expectedId) {
                        throw LnPluginException("now declares the id $declared, installed as $expectedId")
                    }
                    slot.storage.id = trustedId?.takeIf { it == declared }
                    val infoJson = q.evaluate<String>("JSON.stringify(globalThis.__lnLoadPlugin(${jsStr(source)}))")
                    JSON.decodeFromString(LnPluginInfo.serializer(), infoJson)
                }
            } finally {
                closeLocked(slot)
            }
        }
    }

    suspend fun popularNovels(pluginId: String, pageNo: Int, optionsJson: String = "{}"): List<NovelItem> {
        val args = listOf<JsonElement>(JsonPrimitive(pageNo), JSON.parseToJsonElement(optionsJson))
        val raw = callMethod(pluginId, "popularNovels", args)
        return JSON.decodeFromJsonElement(NovelItemsSerializer, raw)
    }

    suspend fun parseNovel(pluginId: String, novelPath: String): SourceNovel {
        val raw = callMethod(pluginId, "parseNovel", listOf(JsonPrimitive(novelPath)))
        return JSON.decodeFromJsonElement(SourceNovel.serializer(), raw)
    }

    suspend fun parseChapter(pluginId: String, chapterPath: String): String {
        return chapterText(callMethod(pluginId, "parseChapter", listOf(JsonPrimitive(chapterPath))))
    }

    /** Optional lnreader `parsePage`: one page of a paged source's chapter list. Null when the plugin
     *  doesn't define it (single-page source) or it errors, mirroring [resolveUrl]. The plugin returns
     *  only `{ chapters }`, so the path comes from the caller. */
    suspend fun parsePage(pluginId: String, novelPath: String, page: String): SourceNovel? = runCatchingCancellable {
        val raw = callMethod(pluginId, "parsePage", listOf(JsonPrimitive(novelPath), JsonPrimitive(page)))
        val parsed = JSON.decodeFromJsonElement(SourcePage.serializer(), raw)
        SourceNovel(path = novelPath, chapters = parsed.chapters)
    }.onFailure {
        // A thrown error here reads to the caller as "no more pages" and silently truncates the
        // chapter list, so at least leave a trace instead of swallowing it blind.
        logcat(LogPriority.DEBUG, it) { "parsePage failed or unsupported for $pluginId" }
    }.getOrNull()

    /** Optional lnreader `resolveUrl`; null when the plugin doesn't define it (most don't) or errors,
     *  so callers fall back to the source site. */
    suspend fun resolveUrl(pluginId: String, path: String, isNovel: Boolean): String? = runCatchingCancellable {
        val raw = callMethod(pluginId, "resolveUrl", listOf(JsonPrimitive(path), JsonPrimitive(isNovel)))
        raw.jsonPrimitive.contentOrNull
    }.getOrNull()

    suspend fun searchNovels(pluginId: String, query: String, pageNo: Int): List<NovelItem> {
        val raw = callMethod(
            pluginId,
            "searchNovels",
            listOf(JsonPrimitive(query), JsonPrimitive(pageNo)),
        )
        return JSON.decodeFromJsonElement(NovelItemsSerializer, raw)
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

    /**
     * Keeps what a WebView on [pluginId]'s site held in its storage, which the plugin reads through
     * LNReader's keyless `localStorage.get()` / `sessionStorage.get()`. Its next call starts a fresh engine,
     * so a plugin that reads the storage once, while it is constructed, sees it then rather than next launch.
     */
    fun storeWebStorage(pluginId: String, storage: WebStorageSnapshot) {
        // headless.js makeWebStorage reads these keys.
        bridge.setStorage(pluginId, "${WEB_STORAGE_KEY_PREFIX}local", storage.local)
        bridge.setStorage(pluginId, "${WEB_STORAGE_KEY_PREFIX}session", storage.session)
        pluginSlots[pluginId]?.rebuild = true
    }

    /** Drops the site storage [storeWebStorage] kept for [pluginId], which carries the site's sign-in. */
    fun forgetWebStorage(pluginId: String) {
        bridge.setStorage(pluginId, "${WEB_STORAGE_KEY_PREFIX}local", null)
        bridge.setStorage(pluginId, "${WEB_STORAGE_KEY_PREFIX}session", null)
        pluginSlots[pluginId]?.rebuild = true
    }

    /** Stops the idle sweeper and closes every engine, returning once they are closed. */
    suspend fun close() {
        synchronized(sweeperLock) {
            sweeperJob?.cancel()
            sweeperJob = null
        }
        val slots = pluginSlots.values.toList()
        pluginSlots.clear()
        slots.forEach { slot -> slot.mutex.withLock { closeLocked(slot) } }
    }

    /** Close a slot's engine and retire its thread. Caller must hold the slot's mutex. */
    private suspend fun closeLocked(slot: EngineSlot) {
        val q = slot.qjs
        slot.qjs = null
        // Close on the engine thread (close() may pump native state), then retire the executor.
        if (q != null) slot.dispatcher?.let { disp -> withContext(disp) { runCatching { q.close() } } }
        slot.executor?.shutdown()
        slot.executor = null
        slot.dispatcher = null
    }

    /** Closes engines nobody has used for [IDLE_CLOSE_MS], so an idle plugin holds no native engine or
     *  thread. Exits when nothing is live; engine creation restarts it. */
    private fun ensureSweeper() {
        synchronized(sweeperLock) {
            if (sweeperJob?.isActive == true) return
            sweeperJob = scope.launch(Dispatchers.Default) {
                while (true) {
                    delay(SWEEP_INTERVAL_MS)
                    var anyLive = false
                    for (slot in pluginSlots.values) {
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
                    // Re-checked under the lock ensureSweeper takes: an engine created after this pass
                    // read its slot saw this job still active and started no sweeper of its own, so
                    // exiting on the pass alone left that engine and its thread open for good.
                    val exiting = !anyLive && synchronized(sweeperLock) {
                        val nothingLive = pluginSlots.values.none { it.qjs != null }
                        if (nothingLive) sweeperJob = null
                        nothingLive
                    }
                    if (exiting) return@launch
                }
            }
        }
    }

    private suspend fun callMethod(
        pluginId: String,
        method: String,
        args: List<JsonElement>,
    ): JsonElement = withPluginTimeout(CALL_TIMEOUT_MS, method) {
        val slot = pluginSlots[pluginId] ?: throw LnPluginException("plugin not loaded: $pluginId")
        var started = false
        try {
            slot.mutex.withLock { callLocked(slot, method, args) { started = true } }
        } catch (e: CancellationException) {
            // The library runs a call's fetches in the engine's own scope, so a timed-out or cancelled call
            // leaves them running, and the next call would wait on them; only closing the engine stops them.
            if (started) slot.rebuild = true
            throw e
        }
    }

    private suspend fun callLocked(
        slot: EngineSlot,
        method: String,
        args: List<JsonElement>,
        onStarted: () -> Unit,
    ): JsonElement {
        if (slot.rebuild) {
            slot.rebuild = false
            closeLocked(slot)
        }
        return slot.onThread {
            val q = slot.engine()
            val failure = slot.fetchFailure
            failure.set(null)
            onStarted()
            val argsJson = JSON.encodeToString(ListSerializer(JsonElement.serializer()), args)
            // __lnCallMethod is async, and evaluate returns the Promise rather than its value, so the
            // settled result parks on a global the engine fills while evaluate pumps the job queue.
            //
            // Each call parks in its OWN slot, keyed by a call id. One shared slot was not safe despite
            // the mutex: a promise outlives the call that created it, so a slow call that gave up could
            // settle later into the slot the NEXT call read, handing one novel another's parsed
            // metadata and writing it over that novel's row.
            val callId = "c${++slot.callSeq}"
            q.evaluate<Any?>(
                "globalThis.__lnResults=globalThis.__lnResults||{};" +
                    // A call that timed out never collects its slot; bound the leak.
                    "if(Object.keys(globalThis.__lnResults).length>$MAX_PENDING_SLOTS)" +
                    "globalThis.__lnResults={};" +
                    "globalThis.__lnCallMethod(${jsStr(method)}, ${jsStr(argsJson)})" +
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
            if (!result.ok) {
                throw LnPluginException(result.error ?: "$method failed without message", failure.get())
            }
            failure.get()?.let { currentCoroutineContext()[FetchFailures]?.record(it) }
            result.value ?: JsonNull
        }
    }

    private fun jsStr(s: String): String = JSON.encodeToString(String.serializer(), s)

    companion object {
        // Reading a script is CPU-only (start an engine, evaluate the plugin code); 30s is ample.
        private const val LOAD_TIMEOUT_MS = 30_000L

        // A bulk load at app start reads every installed script, each in an engine of its own.
        private const val INSPECT_PARALLELISM = 3

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

        // headless.js last: it wires the shims over the vendor globals.
        internal val RUNTIME_ASSETS = listOf(
            "lnhost/vendor/dayjs.min.js",
            "lnhost/vendor/htmlparser2.min.js",
            "lnhost/vendor/cheerio.min.js",
            // protobuf powers @libs/fetch's fetchProto for gRPC-web sources e.g. WuxiaWorld.
            "lnhost/vendor/protobuf.min.js",
            // @noble/ciphers AES-GCM, backs @libs/aes (wtrlab decrypts chapter bodies with it).
            "lnhost/vendor/noble-ciphers.min.js",
            "lnhost/headless.js",
        )

        // Plugin output and the runtime's fetch options both cross here. coerceInputValues reads a null as
        // the field's default: JSON.stringify writes NaN as null, so a NaN page count reads as one page.
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}

/**
 * The storage one engine's plugin reaches, under the id Kotlin bound it to. A null [id] reaches none: the
 * engine reading a script before the installer has accepted the id it declares.
 */
internal class EngineStorage(private val bridge: LnHostBridge, @Volatile var id: String?) {
    fun get(key: String): String? = id?.let { bridge.getStorage(it, key) }

    fun set(key: String, value: String?) {
        id?.let { bridge.setStorage(it, key, value) }
    }
}

class LnPluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A plugin's chapter body. A null or undefined one is no text, which FetchFailures reads as empty; `content` is "null". */
internal fun chapterText(raw: JsonElement): String = raw.jsonPrimitive.contentOrNull.orEmpty()

/**
 * Whether a plugin can serve a Latest listing, derived from its source rather than declared: the
 * lnreader format has no flag for it, so the only evidence is that the plugin reads the option at
 * all. The name is an external contract, so no minifier renames it. A plugin that reaches Latest
 * some other way loses the chip, which is the safe direction: the alternative is a chip that
 * silently re-serves the Popular list.
 */
internal fun derivesLatestSupport(pluginSource: String): Boolean =
    "showLatestNovels" in pluginSource

/**
 * [withTimeout] whose own deadline surfaces as [LnPluginException]. A bare TimeoutCancellationException
 * reads as the caller being cancelled, so every cancellation clause above the host rethrew it, and the
 * migration driver took a timed-out row for an abandoned one and searched it again forever.
 */
internal suspend fun <T : Any> withPluginTimeout(
    timeoutMs: Long,
    what: String,
    block: suspend CoroutineScope.() -> T,
): T = withTimeoutOrNull(timeoutMs, block) ?: throw LnPluginException("$what timed out after ${timeoutMs / 1000}s")
