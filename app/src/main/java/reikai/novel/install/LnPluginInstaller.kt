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
import reikai.domain.novel.NovelPreferences
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginLoader
import reikai.novel.registry.LnRegistry
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.system.logcat

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

    private val loadMutex = Mutex()
    @Volatile private var loadedOnce = false

    /** Load every installed plugin into the app-scoped host exactly once per process, registering
     *  the sources with [NovelSourceManager]. Idempotent and concurrency-safe. */
    suspend fun ensureLoaded() {
        if (loadedOnce) return
        loadMutex.withLock {
            if (loadedOnce) return
            loadInstalled()
            loadedOnce = true
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
        prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() + canonical)
        val record = metadata?.copy(pluginId = info.id)
            ?: LnInstalledPluginMetadata(pluginId = info.id)
        prefs.installedPluginMetadata().set(prefs.installedPluginMetadata().get() + (canonical to record))
        logcat(LogPriority.INFO) { "installed plugin ${info.id} from $canonical" }
        return source
    }

    /**
     * Re-load every URL in [NovelPreferences.installedPluginUrls] into the app-scoped host and
     * register the resulting sources. Individual failures are logged and skipped so one bad URL
     * doesn't block the rest. Lazily backfills missing iconUrl/lang for legacy installs.
     */
    suspend fun loadInstalled(): List<LnPluginSource> {
        val urls = prefs.installedPluginUrls().get()
        val metadata = backfillMetadata(urls)
        return urls.mapNotNull { url ->
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
                source
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "loadInstalled: failed for $url" }
                null
            }
        }
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
     * Remove a plugin's URL from persistence and unregister its source. The loaded plugin instance
     * stays in the host until the host is destroyed; that's fine because the source is no longer
     * reachable through the manager.
     */
    suspend fun uninstall(pluginId: String, pluginJsUrl: String) {
        val canonical = canonicalizePluginUrl(pluginJsUrl)
        prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - canonical)
        prefs.installedPluginMetadata().set(prefs.installedPluginMetadata().get() - canonical)
        manager.unregister(pluginId)
        logcat(LogPriority.INFO) { "uninstalled plugin $pluginId (was $canonical)" }
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
