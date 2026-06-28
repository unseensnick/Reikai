package reikai.novel.install

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs and uninstalls light-novel plugins.
 *
 * - [installFromUrl] downloads + loads + registers + persists the plugin URL.
 * - [loadInstalled] / [ensureLoaded] re-load every previously-installed plugin into the app-scoped
 *   host on app start, registering the sources with [NovelSourceManager].
 * - [fetchRepo] downloads + parses an lnreader registry's `plugins.min.json` index.
 *
 * The installer owns the app-scoped [LnPluginHost]; [ensureLoaded] populates the shared
 * [NovelSourceManager] from persistence once per process.
 */
class LnPluginInstaller(
    private val networkHelper: NetworkHelper,
    private val loader: LnPluginLoader,
    private val manager: NovelSourceManager,
    private val prefs: NovelPreferences,
    private val host: LnPluginHost,
) {

    // Serializes the bulk load so two ensureLoaded calls don't double-load. NOT held by install/
    // uninstall: those only touch the concurrent [loadedUrls] set, so a tap-to-install never blocks
    // behind an in-progress (possibly slow, e.g. a down repo) ensureLoaded.
    private val loadMutex = Mutex()
    // Canonical URLs already loaded + registered this process. ensureLoaded retries only the installed
    // URLs NOT in here, so a plugin whose download failed once (network blip, Cloudflare, cold cache
    // after a restore) heals on the next novel-screen open instead of needing a cold restart.
    private val loadedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Load any installed plugins not yet registered this process, in parallel. Retries previously
     *  failed ones on each call, so navigating to a novel screen self-heals a transient load failure. */
    suspend fun ensureLoaded() {
        loadMutex.withLock {
            loadUrlsLocked(prefs.installedPluginUrls().get() - loadedUrls)
        }
    }

    suspend fun installFromUrl(
        pluginJsUrl: String,
        metadata: LnInstalledPluginMetadata? = null,
    ): LnPluginSource {
        val canonical = canonicalizePluginUrl(pluginJsUrl)
        val src = loader.fetchSource(canonical, forceRefresh = true)
        val info = host.loadPlugin(scopeIdFromUrl(canonical), src, metadata?.iconUrl, metadata?.lang)
        val source = LnPluginSource(host, info)
        manager.register(source)
        rememberSeenSources(listOf(source))

        // A plugin's identity is [info.id], not its URL. Drop any prior install of the same plugin (the
        // same plugin from a different/old repo, or a URL carried in by a restore) so installing
        // REPLACES it instead of leaving a duplicate URL that reloads on the next launch.
        val currentMetadata = prefs.installedPluginMetadata().get()
        val staleUrls = currentMetadata.filterValues { it.pluginId == info.id }.keys - canonical
        val record = metadata?.copy(pluginId = info.id) ?: LnInstalledPluginMetadata(pluginId = info.id)
        prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - staleUrls + canonical)
        prefs.installedPluginMetadata().set(currentMetadata - staleUrls + (canonical to record))
        loadedUrls.removeAll(staleUrls)
        loadedUrls.add(canonical)

        logcat(LogPriority.INFO) { "installed plugin ${info.id} from $canonical" }
        return source
    }

    /**
     * Force a full re-load of every installed plugin (e.g. a manual "reload sources"), retrying any
     * that previously failed. Individual failures are logged and skipped so one bad URL doesn't block
     * the rest. Prefer [ensureLoaded] for the lazy on-open path.
     */
    suspend fun loadInstalled(): List<LnPluginSource> = loadMutex.withLock {
        loadedUrls.clear()
        loadUrlsLocked(prefs.installedPluginUrls().get())
    }

    /**
     * Load [urls] into the app-scoped host in parallel and register the successes. The slow part (the
     * network download per plugin) overlaps; the JS engine eval serializes safely behind the host's
     * own mutex. Successful URLs are recorded in [loadedUrls]; failures are logged and left out so a
     * later [ensureLoaded] retries them. Caller must hold [loadMutex]. Lazily backfills missing
     * iconUrl/lang for legacy installs.
     */
    private suspend fun loadUrlsLocked(urls: Set<String>): List<LnPluginSource> {
        if (urls.isEmpty()) return emptyList()
        val metadata = backfillMetadata(urls)
        val results = coroutineScope {
            urls.map { url ->
                async {
                    try {
                        val src = loader.fetchSource(url, forceRefresh = false)
                        val info = host.loadPlugin(
                            scopeIdFromUrl(url),
                            src,
                            metadata[url]?.iconUrl,
                            metadata[url]?.lang,
                        )
                        val source = LnPluginSource(host, info)
                        manager.register(source)
                        url to source
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "loadInstalled: failed for $url" }
                        null
                    }
                }
            }.awaitAll()
        }
        val ok = results.filterNotNull()
        // Record successes on the single (mutex-holding) coroutine, after awaitAll, to avoid racing on
        // loadedUrls from the parallel children.
        loadedUrls += ok.map { it.first }
        rememberSeenSources(ok.map { it.second })
        return ok.map { it.second }
    }

    /**
     * Cache each loaded source's display identity (name / icon / lang) by plugin id, so the Browse
     * migration list can render a source even after its plugin is uninstalled. Merged in (an install
     * refreshes a renamed source) and never pruned by [uninstall], which is what makes the stub row
     * survive removal.
     */
    private fun rememberSeenSources(sources: List<LnPluginSource>) {
        if (sources.isEmpty()) return
        val current = prefs.seenNovelSources().get()
        val updated = current + sources.associate {
            it.id to LnSourceIdentity(name = it.name, iconUrl = it.iconUrl, lang = it.lang)
        }
        if (updated != current) prefs.seenNovelSources().set(updated)
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
            updated[pluginUrl] = LnInstalledPluginMetadata(
                pluginId = match.id,
                iconUrl = match.iconUrl,
                version = match.version,
                lang = match.lang,
            )
        }
        if (updated != current) {
            prefs.installedPluginMetadata().set(updated.toMap())
        }
        return updated
    }

    /**
     * Remove a plugin from persistence and unregister its source. Removes EVERY URL mapped to this
     * plugin id (a plugin can have several if it was installed from more than one repo or carried in by
     * a restore), so uninstall fully removes it instead of leaving a sibling URL that reloads on the
     * next launch. The loaded plugin instance stays in the host until the host is destroyed; that's
     * fine because the source is no longer reachable through the manager.
     */
    suspend fun uninstall(pluginId: String, pluginJsUrl: String) {
        val metadata = prefs.installedPluginMetadata().get()
        val urlsToRemove = metadata.filterValues { it.pluginId == pluginId }.keys + canonicalizePluginUrl(pluginJsUrl)
        prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - urlsToRemove)
        prefs.installedPluginMetadata().set(metadata - urlsToRemove)
        loadedUrls.removeAll(urlsToRemove)
        manager.unregister(pluginId)
        logcat(LogPriority.INFO) { "uninstalled plugin $pluginId (${urlsToRemove.size} url(s))" }
    }

    /**
     * Fetch + parse an lnreader plugin registry's JSON index. Caller decides what to do with the
     * entries (typically: present a list and call [installFromUrl] for each chosen entry's `url`).
     */
    suspend fun fetchRepo(repoJsonUrl: String): List<LnRegistryEntry> = withContext(Dispatchers.IO) {
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
}

/**
 * Normalize a plugin URL so equality compares predictably across the install/uninstall surface.
 *
 * Registry-emitted URLs leave reserved path characters like `[` and `]` literal (e.g.
 * `NovelBin[readnovelfull].js`); pasted URLs historically had them percent-encoded. Both forms
 * fetch fine, but the `entry.url in installedPluginUrls` check is exact string equality, so the
 * registry form would miss against a stored encoded form. Forcing `[` / `]` to their percent forms
 * collapses the only mismatch observed.
 */
fun canonicalizePluginUrl(url: String): String =
    url.replace("[", "%5B").replace("]", "%5D")
