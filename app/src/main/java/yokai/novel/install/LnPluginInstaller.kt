package yokai.novel.install

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import yokai.domain.novel.LnInstalledPluginMetadata
import yokai.domain.novel.NovelPreferences
import yokai.novel.host.LN_HOST_TAG
import yokai.novel.host.LnPluginHost
import yokai.novel.host.LnPluginLoader
import yokai.novel.registry.LnRegistry
import yokai.novel.registry.LnRegistryEntry
import yokai.novel.source.LnPluginSource
import yokai.novel.source.NovelSourceManager

/**
 * Installs and uninstalls light-novel plugins.
 *
 * Slice C1 surface:
 * - [installFromUrl] downloads + loads + registers + persists the plugin URL.
 * - [loadInstalled] re-loads every previously-installed plugin into a fresh host on screen open
 *   (called by the probe; later, by product UI that needs LN sources).
 * - [fetchRepo] downloads + parses an lnreader registry's `plugins.min.json` index. Slice C2's
 *   add-repo screen consumes this; included here so the registry-parser + NetworkHelper hookup
 *   gets one home.
 *
 * The installer owns the app-scoped [LnPluginHost] (a Koin `single`). [ensureLoaded] populates the
 * shared [NovelSourceManager] from persistence once per process; screens call it on entry instead of
 * building their own host.
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
     *  the sources with [NovelSourceManager]. Idempotent and concurrency-safe: the first caller does
     *  the work, the rest return immediately. Screens call this on entry (replaces the old
     *  per-screen `loadInstalled(host)`). */
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
        Logger.i(LN_HOST_TAG) { "installed plugin ${info.id} from $canonical" }
        return source
    }

    /**
     * Re-load every URL in [NovelPreferences.installedPluginUrls] into the app-scoped host and
     * register the resulting sources with [NovelSourceManager]. Individual failures are logged and skipped
     * so one bad URL doesn't block the rest.
     *
     * Performs a lazy iconUrl backfill for legacy installs (URLs persisted before the metadata
     * pref existed, or installs that bypassed the registry): fetches each added repo once,
     * matches by canonical URL, writes the resolved metadata back. Backfill runs only when at
     * least one URL is missing iconUrl, so steady-state launches incur no extra HTTP work.
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
                Logger.e(LN_HOST_TAG, e) { "loadInstalled: failed for $url" }
                null
            }
        }
    }

    /**
     * Returns a metadata map covering [urls], populating any URL whose stored metadata lacks an
     * iconUrl or a lang by scanning every added repo's registry once. Writes resolved records
     * back to persistence so subsequent loads skip the network. Returns the current map
     * unchanged when nothing needs backfilling.
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
                        Logger.w(LN_HOST_TAG, it) { "backfill: fetch failed for $repoUrl" }
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
     * Remove a plugin's URL from persistence and unregister its source from the manager. The
     * loaded plugin instance stays in the host's WebView until the host is destroyed; that's
     * fine because the source is no longer reachable through the manager.
     */
    suspend fun uninstall(pluginId: String, pluginJsUrl: String) {
        val canonical = canonicalizePluginUrl(pluginJsUrl)
        prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - canonical)
        prefs.installedPluginMetadata().set(prefs.installedPluginMetadata().get() - canonical)
        manager.unregister(pluginId)
        Logger.i(LN_HOST_TAG) { "uninstalled plugin $pluginId (was $canonical)" }
    }

    /**
     * Fetch + parse an lnreader plugin registry's JSON index. Caller decides what to do with the
     * entries (typically: present a list and call [installFromUrl] for each user-chosen entry's
     * `url`).
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
     * Derive a stable per-URL identifier used as the `@libs/storage` scope at load time. This is
     * unrelated to the plugin's canonical id (which the host resolves from the plugin's own
     * source after evaluation, see commit `abc789dfc`).
     */
    private fun scopeIdFromUrl(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.')
}

/**
 * Normalize a plugin URL so equality compares predictably across the install/uninstall surface.
 *
 * Registry-emitted URLs leave reserved path characters like `[` and `]` literal (e.g.
 * `NovelBin[readnovelfull].js`); URLs the soak operator pasted historically had them
 * percent-encoded (`NovelBin%5B...%5D.js`). Both forms parse and fetch fine, but the
 * `entry.url in installedPluginUrls` check is exact string equality — without normalization, the
 * registry form misses against the stored form. (We initially tried round-tripping through
 * `HttpUrl.toString()`, but OkHttp preserves whichever form was input rather than normalizing
 * to one.)
 *
 * Solution: force `[` and `]` to their percent-encoded forms. This is enough to collapse the
 * only mismatch the soak surfaced. Add more substitutions here if a future registry source uses
 * other reserved characters in filenames.
 */
fun canonicalizePluginUrl(url: String): String =
    url.replace("[", "%5B").replace("]", "%5D")
