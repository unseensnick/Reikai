package reikai.novel.install

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginLoader
import reikai.novel.registry.LnRegistry
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRegistryFetcher
import reikai.novel.registry.LnRepoResult
import reikai.novel.registry.fetchEach
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelChapterStylesheet
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs and uninstalls light-novel plugins, and owns the app-scoped [LnPluginHost].
 * [installFromUrl] downloads, loads, registers and persists a plugin URL; [loadInstalled] and
 * [ensureLoaded] re-load every installed plugin into the host on app start, populating the shared
 * [NovelSourceManager] from persistence once per process; [fetchRepo] parses a registry's
 * `plugins.min.json` index.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LnPluginInstaller(
    private val networkHelper: NetworkHelper,
    private val loader: LnPluginLoader,
    private val manager: NovelSourceManager,
    private val prefs: NovelPreferences,
    private val host: LnPluginHost,
) : LnRegistryFetcher {

    // Serializes the bulk load so two ensureLoaded calls don't double-load. Deliberately NOT held by
    // install/uninstall, so a tap-to-install never blocks behind an in-progress (possibly slow, e.g. a
    // down repo) ensureLoaded; those meet a pass only on the one plugin's lock in [urlLocks].
    // ExtensionManager.loadMutex holds the app scans the same way, so a reload cannot be overwritten by
    // an in-flight load on either side (content-layer-browse-surface.md).
    private val loadMutex = Mutex()

    // One lock per canonical plugin URL, held by a pass's load of it and by an install or uninstall of
    // it, so neither publishes over the other while different plugins never wait. Always taken before
    // [registryMutex], and by uninstall in sorted order.
    private val urlLocks = ConcurrentHashMap<String, Mutex>()

    // Guards every read-modify-write of the two persisted registries (installed urls, installed
    // metadata); the seen sources are the manager's, which both kinds write. Update-all fans installs
    // out in parallel, so without this two of them read the same map, and the slower write drops the
    // other plugin's record. Never held across network work: a repo fetch happens outside it and the
    // map is re-read inside.
    private val registryMutex = Mutex()

    // Canonical URLs already loaded + registered this process. ensureLoaded retries only the installed
    // URLs NOT in here, so a plugin whose load failed once (a missing script's download hitting a
    // network blip or Cloudflare) heals on the next novel-screen open instead of needing a cold restart.
    private val loadedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Installed plugins whose last load failed, by canonical URL. Kept until a load or install of that
     * URL succeeds or it is uninstalled, so the Extensions list can offer the reason and an uninstall.
     */
    val failures: StateFlow<Map<String, LnPluginLoadFailure>>
        field = MutableStateFlow<Map<String, LnPluginLoadFailure>>(emptyMap())

    /** Whether a load pass has run this process, whatever it loaded; see [awaitFirstLoad]. */
    @Volatile
    private var firstLoadDone = false

    /** Load any installed plugins not yet registered this process, in parallel. Retries previously
     *  failed ones on each call, so navigating to a novel screen self-heals a transient load failure. */
    suspend fun ensureLoaded() {
        loadMutex.withLock { loadPendingLocked() }
    }

    /**
     * Runs the first load pass if none has run yet, then returns without retrying anything, as Mihon's
     * source manager awaits its first extension scan. A lookup calls this, so a broken plugin is not
     * loaded again per novel or per chapter; [ensureLoaded] at a screen or run start does the retrying.
     */
    suspend fun awaitFirstLoad() {
        if (firstLoadDone) return
        loadMutex.withLock { if (!firstLoadDone) loadPendingLocked() }
    }

    // Not marked done when cancelled, so a screen closed mid-load leaves the next lookup to load.
    private suspend fun loadPendingLocked() {
        loadUrlsLocked(prefs.installedPluginUrls().get() - loadedUrls)
        firstLoadDone = true
    }

    suspend fun installFromUrl(
        pluginJsUrl: String,
        metadata: LnInstalledPluginMetadata? = null,
    ): LnPluginSource {
        val canonical = canonicalizePluginUrl(pluginJsUrl)
        return withUrlLocks(listOf(canonical)) { installLocked(canonical, metadata) }
    }

    private suspend fun installLocked(canonical: String, metadata: LnInstalledPluginMetadata?): LnPluginSource {
        val src = loader.download(canonical)
        val info = host.loadPlugin(scopeIdFromUrl(canonical), src, metadata?.iconUrl, metadata?.lang)
        // Stored only once it loads, so a broken new version leaves the installed one in place.
        loader.store(canonical, src)
        val source = LnPluginSource(host, info, refreshStylesheet(canonical, metadata?.customCssUrl))

        // A plugin's identity is [info.id], not its URL. Drop any prior install of the same plugin (the
        // same plugin from a different/old repo, or a URL carried in by a restore) so installing
        // REPLACES it instead of leaving a duplicate URL that reloads on the next launch. Registered in
        // the same lock as the stale URLs leave, which a pass loading one of them checks before it publishes.
        val staleUrls = registryMutex.withLock {
            val currentMetadata = prefs.installedPluginMetadata().get()
            val stale = currentMetadata.filterValues { it.pluginId == info.id }.keys - canonical
            val record = (metadata ?: LnInstalledPluginMetadata(pluginId = info.id))
                .copy(pluginId = info.id, version = info.version ?: metadata?.version)
            prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - stale + canonical)
            prefs.installedPluginMetadata().set(currentMetadata - stale + (canonical to record))
            manager.register(source)
            loadedUrls.removeAll(stale)
            loadedUrls.add(canonical)
            failures.update { it - stale - canonical }
            stale
        }
        rememberSeenSources(listOf(source))
        staleUrls.forEach { loader.delete(it) }

        logcat(LogPriority.INFO) { "installed plugin ${info.id} from $canonical" }
        return source
    }

    /**
     * Force a full re-load of every installed plugin (e.g. a manual "reload sources"), retrying any
     * that previously failed. Individual failures are logged and skipped so one bad URL doesn't block
     * the rest. Prefer [ensureLoaded] for the lazy on-open path.
     */
    suspend fun loadInstalled() {
        loadMutex.withLock {
            loadedUrls.clear()
            loadUrlsLocked(prefs.installedPluginUrls().get())
        }
    }

    /**
     * Load [urls] into the app-scoped host in parallel and register the successes, each from its stored
     * script. The reads overlap; the JS engine eval serializes safely behind the host's own mutex. Successful URLs are recorded in [loadedUrls]; failures go to [failures] and are left
     * out so a later [ensureLoaded] retries them. Caller must hold [loadMutex]. Lazily backfills missing
     * iconUrl/lang for legacy installs.
     */
    private suspend fun loadUrlsLocked(urls: Set<String>): List<LnPluginSource> {
        if (urls.isEmpty()) return emptyList()
        val metadata = backfillMetadata(urls)
        val loaded = coroutineScope {
            urls.map { url -> async { withUrlLocks(listOf(url)) { loadUrl(url, metadata[url]) } } }.awaitAll()
        }.filterNotNull()
        rememberSeenSources(loaded)
        return loaded
    }

    /**
     * Loads one installed plugin from its stored script and publishes the outcome for [url]: the source,
     * its version, or its failure. Caller holds [url]'s lock, so an install or uninstall of it that ran
     * while this pass waited is seen here; one that removed [url] as another URL's stale copy is seen at
     * publishing. Null when it failed or is no longer installed.
     */
    private suspend fun loadUrl(url: String, metadata: LnInstalledPluginMetadata?): LnPluginSource? {
        if (url !in prefs.installedPluginUrls().get()) return null
        val loaded = try {
            val stored = loader.installed(url)
            val src = stored ?: downloadMissingScript(url)
            val info = host.loadPlugin(scopeIdFromUrl(url), src, metadata?.iconUrl, metadata?.lang)
            if (stored == null) loader.store(url, src)
            // A missing script means the stylesheet went with it, so it is fetched again with it.
            val stylesheet = if (stored == null) {
                refreshStylesheet(url, metadata?.customCssUrl)
            } else {
                loader.installedStylesheet(url)?.let(::NovelChapterStylesheet)
            }
            Result.success(LnPluginSource(host, info, stylesheet))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "loadInstalled: failed for $url" }
            Result.failure(e)
        }
        return registryMutex.withLock {
            if (url !in prefs.installedPluginUrls().get()) return@withLock null
            loaded.onSuccess { source ->
                manager.register(source)
                loadedUrls += url
                failures.update { it - url }
                recordLoadedVersion(url, source)
            }.onFailure { error ->
                val seen = prefs.seenNovelSources().get()[metadata?.pluginId]
                failures.update { it + (url to LnPluginLoadFailure.of(url, error, metadata, seen)) }
            }.getOrNull()
        }
    }

    /** Takes [urls]' locks in sorted order, so two callers locking overlapping sets cannot deadlock. */
    private suspend fun <T> withUrlLocks(urls: Collection<String>, block: suspend () -> T): T {
        val held = ArrayList<Mutex>()
        try {
            urls.toSortedSet().forEach { url ->
                val lock = urlLocks.getOrPut(url) { Mutex() }
                lock.lock()
                held += lock
            }
            return block()
        } finally {
            held.asReversed().forEach { it.unlock() }
        }
    }

    /**
     * Stores the chapter stylesheet the registry names for the plugin at [pluginUrl], or removes the stored
     * one when it names none, as LNReader does on install. A failed fetch keeps the stored one rather than
     * failing the install over styling.
     */
    private suspend fun refreshStylesheet(pluginUrl: String, cssUrl: String?): NovelChapterStylesheet? {
        val css = cssUrl?.let {
            try {
                loader.download(it)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "plugin stylesheet download failed: $it" }
                return loader.installedStylesheet(pluginUrl)?.let(::NovelChapterStylesheet)
            }
        }
        loader.storeStylesheet(pluginUrl, css)
        return css?.let(::NovelChapterStylesheet)
    }

    /**
     * Fetch the script for an installed plugin this device has none stored for, which a cleared data dir
     * loses, so the plugin is not left listed as installed with nothing able to run until the reader
     * reinstalls it by hand. Trust is unchanged: the URL is one the user installed. A fetch that fails
     * still reports the script as missing.
     */
    private suspend fun downloadMissingScript(url: String): String = try {
        loader.download(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw LnPluginScriptMissingException(url, e)
    }

    /**
     * Records the version a plugin reports for itself, which is the one actually running. The repo's
     * version is not: a record written from it, or a URL pasted without one, would hide an update.
     * Caller holds [registryMutex] and has checked [url] is still installed.
     */
    private fun recordLoadedVersion(url: String, source: LnPluginSource) {
        val version = source.version.ifEmpty { return }
        val current = prefs.installedPluginMetadata().get()
        val record = current[url] ?: LnInstalledPluginMetadata(pluginId = source.id)
        if (record.version == version) return
        prefs.installedPluginMetadata().set(current + (url to record.copy(version = version)))
    }

    /**
     * Cache each loaded source's display identity (name / icon / lang) by plugin id, so the Browse
     * migration list can render a source even after its plugin is uninstalled. Merged in (an install
     * refreshes a renamed source) and never pruned by [uninstall], which is what makes the stub row
     * survive removal.
     */
    private suspend fun rememberSeenSources(sources: List<LnPluginSource>) {
        manager.rememberSeen(
            sources.associate {
                it.id to LnSourceIdentity(
                    name = it.name,
                    iconUrl = it.iconUrl,
                    lang = it.lang,
                    site = it.site,
                    imageHeaders = it.imageHeaders,
                )
            },
        )
    }

    /**
     * Returns a metadata map covering [urls], populating any URL whose stored metadata lacks an
     * iconUrl or lang by scanning every added repo's registry once and writing resolved records
     * back. Returns the current map unchanged when nothing needs backfilling.
     */
    private suspend fun backfillMetadata(urls: Set<String>): Map<String, LnInstalledPluginMetadata> {
        val current = prefs.installedPluginMetadata().get()
        val needs = urls.filter {
            val record = current[it]
            record?.iconUrl == null || record.lang == null
        }
        if (needs.isEmpty()) return current
        val repos = prefs.addedRepoUrls().get()
        if (repos.isEmpty()) return current
        val entries = fetchEach(repos).values.flatMap { (it as? LnRepoResult.Reached)?.entries.orEmpty() }
        if (entries.isEmpty()) return current
        val updated = current.toMutableMap()
        needs.forEach { pluginUrl ->
            val match = entries.firstOrNull { canonicalizePluginUrl(it.url) == pluginUrl }
                ?: return@forEach
            // The version stays whatever was recorded: the repo's is not necessarily the one installed.
            updated[pluginUrl] = (current[pluginUrl] ?: LnInstalledPluginMetadata(pluginId = match.id))
                .copy(iconUrl = match.iconUrl, lang = match.lang)
        }
        if (updated == current) return current
        // Re-read under the lock rather than writing the snapshot taken before the repo fetch above:
        // an install can land during it, and only the backfilled keys belong to this pass.
        return registryMutex.withLock {
            val latest = prefs.installedPluginMetadata().get()
            val merged = latest + needs.mapNotNull { url -> updated[url]?.let { url to it } }
            if (merged != latest) prefs.installedPluginMetadata().set(merged)
            merged
        }
    }

    /**
     * Remove a plugin from persistence and unregister its source. Removes EVERY URL mapped to this
     * plugin id (a plugin can have several if it was installed from more than one repo or carried in by
     * a restore), so uninstall fully removes it instead of leaving a sibling URL that reloads on the
     * next launch. The loaded plugin instance stays in the host until the host is destroyed; that's
     * fine because the source is no longer reachable through the manager. Its settings and logins stay
     * for a reinstall, as a Mihon extension's source preferences outlive its uninstall.
     */
    suspend fun uninstall(pluginId: String, pluginJsUrl: String? = null) {
        // Resolve the URL(s) to drop from the plugin id against FRESH metadata, not a caller-cached
        // snapshot: a UI map built off manager.sources can lag a same-session install and would strand
        // the plugin. Resolved again under the locks, for an install that landed while this waited.
        val explicit = pluginJsUrl?.let { setOf(canonicalizePluginUrl(it)) }.orEmpty()
        val locked = registryMutex.withLock { urlsOf(pluginId) } + explicit
        val urlsToRemove = withUrlLocks(locked) {
            val remove = registryMutex.withLock {
                val remove = urlsOf(pluginId) + explicit
                prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - remove)
                prefs.installedPluginMetadata().set(prefs.installedPluginMetadata().get() - remove)
                loadedUrls.removeAll(remove)
                failures.update { it - remove }
                manager.unregister(pluginId)
                remove
            }
            remove.forEach { loader.delete(it) }
            remove
        }
        logcat(LogPriority.INFO) { "uninstalled plugin $pluginId (${urlsToRemove.size} url(s))" }
    }

    private fun urlsOf(pluginId: String): Set<String> =
        prefs.installedPluginMetadata().get().filterValues { it.pluginId == pluginId }.keys

    /**
     * Fetch + parse an lnreader plugin registry's JSON index. Caller decides what to do with the
     * entries (typically: present a list and call [installFromUrl] for each chosen entry's `url`).
     */
    override suspend fun fetchRepo(repoJsonUrl: String): List<LnRegistryEntry> = withContext(Dispatchers.IO) {
        networkHelper.client.newCall(GET(repoJsonUrl)).awaitSuccess().use { res ->
            LnRegistry.parse(res.body.string())
        }
    }

    /**
     * A per-URL id the host loads a plugin under before it knows the plugin's own. Storage is scoped by
     * the plugin's own id, which `headless.js` discovers on a first pass; this one scopes it only when
     * that discovery throws.
     */
    private fun scopeIdFromUrl(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.')
}

/**
 * Normalize a plugin URL so equality compares predictably across the install/uninstall surface.
 * Registry-emitted URLs leave reserved path characters like `[` and `]` literal, where pasted URLs had
 * them percent-encoded. Both fetch fine, but `entry.url in installedPluginUrls` is exact string
 * equality, so the registry form missed against a stored encoded one. Forcing `[` and `]` to their
 * percent forms collapses the only mismatch observed.
 */
fun canonicalizePluginUrl(url: String): String =
    url.replace("[", "%5B").replace("]", "%5D")
