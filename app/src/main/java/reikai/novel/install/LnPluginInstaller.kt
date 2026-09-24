package reikai.novel.install

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
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
import okhttp3.Request
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginLoader
import reikai.novel.registry.LnRegistry
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRegistryFetcher
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
    // down repo) ensureLoaded; those serialize their own writes on registryMutex instead.
    private val loadMutex = Mutex()

    // Guards every read-modify-write of the two persisted registries (installed urls, installed
    // metadata); the seen sources are the manager's, which both kinds write. Update-all fans installs
    // out in parallel, so without this two of them read the same map, and the slower write drops the
    // other plugin's record. Never held across network work: a repo fetch happens outside it and the
    // map is re-read inside.
    private val registryMutex = Mutex()

    // Canonical URLs already loaded + registered this process. ensureLoaded retries only the installed
    // URLs NOT in here, so a plugin whose load failed once (a restored script's download hitting a
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
        // Plugin URLs from a restored backup are untrusted until a currently-added repo vouches
        // for them; if a repo is unreachable, load nothing this pass so an injected URL can't slip
        // through, and retry on the next open.
        val needsTrust = prefs.pluginsNeedRevalidation().get()
        if (!needsTrust || revalidateInstalledAgainstReposLocked() !is Revalidated.Unreachable) {
            loadUrlsLocked(prefs.installedPluginUrls().get() - loadedUrls)
        }
        firstLoadDone = true
    }

    /**
     * After a backup restore, keep only installed plugin URLs that a currently-added repo lists in its
     * registry, and drop the rest. A restored backup can inject arbitrary plugin .js URLs that the
     * host would auto-load and evaluate, so this is the trust gate: a plugin is trusted only because
     * it came from a repo the user added. When a repo is unreachable nothing is dropped or loaded and the
     * caller retries on the next open (fail-closed). Caller must hold [loadMutex].
     */
    private suspend fun revalidateInstalledAgainstReposLocked(): Revalidated {
        val trusted = HashSet<String>()
        for (repo in prefs.addedRepoUrls().get()) {
            try {
                fetchRepo(repo).forEach { trusted += canonicalizePluginUrl(it.url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "plugin revalidation: repo unreachable, retrying next open: $repo" }
                return Revalidated.Unreachable(repo)
            }
        }
        val dropped = registryMutex.withLock {
            val installed = prefs.installedPluginUrls().get()
            val metadata = prefs.installedPluginMetadata().get()
            val validated = installed.filterTo(HashSet()) { it in trusted }
            if (validated.size != installed.size) {
                prefs.installedPluginUrls().set(validated)
                prefs.installedPluginMetadata().set(metadata.filterKeys { it in validated })
            }
            val seen = prefs.seenNovelSources().get()
            (installed - validated).map { LnPluginLoadFailure.pluginName(it, seen[metadata[it]?.pluginId]) }
        }
        if (dropped.isNotEmpty()) {
            logcat(LogPriority.WARN) {
                "plugin revalidation: dropped ${dropped.size} url(s) not vouched by any added repo"
            }
        }
        prefs.pluginsNeedRevalidation().set(false)
        return Revalidated.Trusted(dropped)
    }

    private sealed interface Revalidated {
        /** Safe to load; [dropped] names the restored plugins no added repo lists, which were removed. */
        data class Trusted(val dropped: List<String>) : Revalidated

        data class Unreachable(val repo: String) : Revalidated
    }

    suspend fun installFromUrl(
        pluginJsUrl: String,
        metadata: LnInstalledPluginMetadata? = null,
    ): LnPluginSource {
        val canonical = canonicalizePluginUrl(pluginJsUrl)
        val src = loader.download(canonical)
        val info = host.loadPlugin(scopeIdFromUrl(canonical), src, metadata?.iconUrl, metadata?.lang)
        // Stored only once it loads, so a broken new version leaves the installed one in place.
        loader.store(canonical, src)
        val source = LnPluginSource(host, info, refreshStylesheet(canonical, metadata?.customCssUrl))
        manager.register(source)
        rememberSeenSources(listOf(source))

        // A plugin's identity is [info.id], not its URL. Drop any prior install of the same plugin (the
        // same plugin from a different/old repo, or a URL carried in by a restore) so installing
        // REPLACES it instead of leaving a duplicate URL that reloads on the next launch.
        val staleUrls = registryMutex.withLock {
            val currentMetadata = prefs.installedPluginMetadata().get()
            val stale = currentMetadata.filterValues { it.pluginId == info.id }.keys - canonical
            val record = (metadata ?: LnInstalledPluginMetadata(pluginId = info.id))
                .copy(pluginId = info.id, version = info.version ?: metadata?.version)
            prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - stale + canonical)
            prefs.installedPluginMetadata().set(currentMetadata - stale + (canonical to record))
            stale
        }
        staleUrls.forEach { loader.delete(it) }
        loadedUrls.removeAll(staleUrls)
        loadedUrls.add(canonical)
        failures.update { it - staleUrls - canonical }

        logcat(LogPriority.INFO) { "installed plugin ${info.id} from $canonical" }
        return source
    }

    /**
     * Force a full re-load of every installed plugin (e.g. a manual "reload sources"), retrying any
     * that previously failed. Individual failures are logged and skipped so one bad URL doesn't block
     * the rest. Prefer [ensureLoaded] for the lazy on-open path.
     */
    suspend fun loadInstalled(): InstalledLoad = loadMutex.withLock {
        val revalidated = if (prefs.pluginsNeedRevalidation().get()) revalidateInstalledAgainstReposLocked() else null
        if (revalidated is Revalidated.Unreachable) return@withLock InstalledLoad(unreachableRepo = revalidated.repo)
        loadedUrls.clear()
        InstalledLoad(
            loaded = loadUrlsLocked(prefs.installedPluginUrls().get()),
            dropped = (revalidated as? Revalidated.Trusted)?.dropped.orEmpty(),
        )
    }

    /** What [loadInstalled] did, including why a restored plugin list loaded nothing or lost some. */
    data class InstalledLoad(
        val loaded: List<LnPluginSource> = emptyList(),
        /** An added repo that could not be reached, so a restored list stayed untrusted and nothing loaded. */
        val unreachableRepo: String? = null,
        /** Restored plugins no added repo lists, by name; they were removed rather than loaded. */
        val dropped: List<String> = emptyList(),
    )

    /**
     * Load [urls] into the app-scoped host in parallel and register the successes, each from its stored
     * script. The reads overlap; the JS engine eval serializes safely behind the host's own mutex. Successful URLs are recorded in [loadedUrls]; failures go to [failures] and are left
     * out so a later [ensureLoaded] retries them. Caller must hold [loadMutex]. Lazily backfills missing
     * iconUrl/lang for legacy installs.
     */
    private suspend fun loadUrlsLocked(urls: Set<String>): List<LnPluginSource> {
        if (urls.isEmpty()) return emptyList()
        val metadata = backfillMetadata(urls)
        val results = coroutineScope {
            urls.map { url ->
                async {
                    try {
                        val stored = loader.installed(url)
                        val src = stored ?: downloadMissingScript(url)
                        val info = host.loadPlugin(
                            scopeIdFromUrl(url),
                            src,
                            metadata[url]?.iconUrl,
                            metadata[url]?.lang,
                        )
                        if (stored == null) loader.store(url, src)
                        // A missing script means the stylesheet went with it, so it is fetched again with it.
                        val stylesheet = if (stored == null) {
                            refreshStylesheet(url, metadata[url]?.customCssUrl)
                        } else {
                            loader.installedStylesheet(url)?.let(::NovelChapterStylesheet)
                        }
                        val source = LnPluginSource(host, info, stylesheet)
                        manager.register(source)
                        LoadResult.Loaded(url, source, info.version)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "loadInstalled: failed for $url" }
                        LoadResult.Failed(url, e)
                    }
                }
            }.awaitAll()
        }
        val ok = results.filterIsInstance<LoadResult.Loaded>()
        // Record the outcomes on the single (mutex-holding) coroutine, after awaitAll, to avoid racing
        // on loadedUrls from the parallel children.
        loadedUrls += ok.map { it.url }
        val seen = prefs.seenNovelSources().get()
        val installed = prefs.installedPluginUrls().get()
        failures.update { current ->
            (current - ok.map { it.url }.toSet()).filterKeys { it in installed } +
                results.filterIsInstance<LoadResult.Failed>().associate { failed ->
                    val record = metadata[failed.url]
                    failed.url to LnPluginLoadFailure.of(failed.url, failed.error, record, seen[record?.pluginId])
                }
        }
        rememberSeenSources(ok.map { it.source })
        recordLoadedVersions(ok)
        return ok.map { it.source }
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
     * Fetch the script for an installed plugin this device has none stored for. A backup carries the
     * URL list but never the scripts, and a cleared data dir loses them, which used to leave the
     * plugin listed as installed with nothing able to run until the reader reinstalled it by hand.
     * Trust is unchanged: the URL is one they installed, or one revalidation kept because an added
     * repo vouches for it. A fetch that fails still reports the script as missing.
     */
    private suspend fun downloadMissingScript(url: String): String = try {
        loader.download(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw LnPluginScriptMissingException(url, e)
    }

    /**
     * Records the version each plugin reports for itself, which is the one actually running. The repo's
     * version is not: a record written from it, or a URL pasted without one, would hide an update.
     */
    private suspend fun recordLoadedVersions(loaded: List<LoadResult.Loaded>) {
        registryMutex.withLock {
            val current = prefs.installedPluginMetadata().get()
            val updated = current + loaded.mapNotNull { result ->
                val version = result.version ?: return@mapNotNull null
                val record = current[result.url] ?: LnInstalledPluginMetadata(pluginId = result.source.id)
                if (record.version == version) null else result.url to record.copy(version = version)
            }
            if (updated != current) prefs.installedPluginMetadata().set(updated)
        }
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
        val entries: List<LnRegistryEntry> = coroutineScope {
            repos.map { repoUrl ->
                async {
                    runCatching { fetchRepo(repoUrl) }.getOrElse {
                        logcat(LogPriority.WARN, it) { "backfill: fetch failed for $repoUrl" }
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
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
     * fine because the source is no longer reachable through the manager.
     */
    suspend fun uninstall(pluginId: String, pluginJsUrl: String? = null) {
        // Resolve the URL(s) to drop from the plugin id against FRESH metadata, not a caller-cached
        // snapshot: [installFromUrl] registers the source before persisting its metadata, so a UI map
        // built off manager.sources lags a same-session install and would strand the plugin.
        val urlsToRemove = registryMutex.withLock {
            val metadata = prefs.installedPluginMetadata().get()
            val remove = metadata.filterValues { it.pluginId == pluginId }.keys +
                (pluginJsUrl?.let { setOf(canonicalizePluginUrl(it)) } ?: emptySet())
            prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - remove)
            prefs.installedPluginMetadata().set(metadata - remove)
            remove
        }
        urlsToRemove.forEach { loader.delete(it) }
        loadedUrls.removeAll(urlsToRemove)
        failures.update { it - urlsToRemove }
        manager.unregister(pluginId)
        logcat(LogPriority.INFO) { "uninstalled plugin $pluginId (${urlsToRemove.size} url(s))" }
    }

    /**
     * Fetch + parse an lnreader plugin registry's JSON index. Caller decides what to do with the
     * entries (typically: present a list and call [installFromUrl] for each chosen entry's `url`).
     */
    override suspend fun fetchRepo(repoJsonUrl: String): List<LnRegistryEntry> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(repoJsonUrl).build()
        networkHelper.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                error("registry fetch failed: HTTP ${res.code} from $repoJsonUrl")
            }
            LnRegistry.parse(res.body.string())
        }
    }

    /**
     * Derive a stable per-URL identifier used as the `@libs/storage` scope at load time. Unrelated
     * to the plugin's canonical id (which the host resolves from the plugin's own source).
     */
    private fun scopeIdFromUrl(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.')

    private sealed interface LoadResult {
        val url: String

        data class Loaded(override val url: String, val source: LnPluginSource, val version: String?) : LoadResult

        data class Failed(override val url: String, val error: Throwable) : LoadResult
    }
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
