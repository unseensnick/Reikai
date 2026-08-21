package reikai.novel.install

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
 * Installs and uninstalls light-novel plugins, and owns the app-scoped [LnPluginHost].
 * [installFromUrl] downloads, loads, registers and persists a plugin URL; [loadInstalled] and
 * [ensureLoaded] re-load every installed plugin into the host on app start, populating the shared
 * [NovelSourceManager] from persistence once per process; [fetchRepo] parses a registry's
 * `plugins.min.json` index.
 */
@Inject
@SingleIn(AppScope::class)
class LnPluginInstaller(
    private val networkHelper: NetworkHelper,
    private val loader: LnPluginLoader,
    private val manager: NovelSourceManager,
    private val prefs: NovelPreferences,
    private val host: LnPluginHost,
) {

    // Serializes the bulk load so two ensureLoaded calls don't double-load. Deliberately NOT held by
    // install/uninstall, so a tap-to-install never blocks behind an in-progress (possibly slow, e.g. a
    // down repo) ensureLoaded; those serialize their own writes on registryMutex instead.
    private val loadMutex = Mutex()

    // Guards every read-modify-write of the three persisted registries (installed urls, installed
    // metadata, seen sources). Update-all fans installs out in parallel, so without this two of them
    // read the same map, and the slower write drops the other plugin's record. Never held across
    // network work: a repo fetch happens outside it and the map is re-read inside.
    private val registryMutex = Mutex()

    // Canonical URLs already loaded + registered this process. ensureLoaded retries only the installed
    // URLs NOT in here, so a plugin whose download failed once (network blip, Cloudflare, cold cache
    // after a restore) heals on the next novel-screen open instead of needing a cold restart.
    private val loadedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Load any installed plugins not yet registered this process, in parallel. Retries previously
     *  failed ones on each call, so navigating to a novel screen self-heals a transient load failure. */
    suspend fun ensureLoaded() {
        loadMutex.withLock {
            // Plugin URLs from a restored backup are untrusted until a currently-added repo vouches
            // for them; if a repo is unreachable, load nothing this pass so an injected URL can't slip
            // through, and retry on the next open.
            if (prefs.pluginsNeedRevalidation().get() && !revalidateInstalledAgainstReposLocked()) {
                return
            }
            loadUrlsLocked(prefs.installedPluginUrls().get() - loadedUrls)
        }
    }

    /**
     * After a backup restore, keep only installed plugin URLs that a currently-added repo lists in its
     * registry, and drop the rest. A restored backup can inject arbitrary plugin .js URLs that the
     * host would auto-load and evaluate, so this is the trust gate: a plugin is trusted only because
     * it came from a repo the user added. Returns true when validation completed (safe to load); false
     * when a repo was unreachable, in which case nothing is dropped or loaded and the caller retries
     * on the next open (fail-closed). Caller must hold [loadMutex].
     */
    private suspend fun revalidateInstalledAgainstReposLocked(): Boolean {
        val trusted = HashSet<String>()
        for (repo in prefs.addedRepoUrls().get()) {
            try {
                fetchRepo(repo).forEach { trusted += canonicalizePluginUrl(it.url) }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "plugin revalidation: repo unreachable, retrying next open: $repo" }
                return false
            }
        }
        val dropped = registryMutex.withLock {
            val installed = prefs.installedPluginUrls().get()
            val validated = installed.filterTo(HashSet()) { it in trusted }
            if (validated.size == installed.size) {
                0
            } else {
                prefs.installedPluginUrls().set(validated)
                prefs.installedPluginMetadata().set(
                    prefs.installedPluginMetadata().get().filterKeys { it in validated },
                )
                installed.size - validated.size
            }
        }
        if (dropped > 0) {
            logcat(LogPriority.WARN) { "plugin revalidation: dropped $dropped url(s) not vouched by any added repo" }
        }
        prefs.pluginsNeedRevalidation().set(false)
        return true
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
        val staleUrls = registryMutex.withLock {
            val currentMetadata = prefs.installedPluginMetadata().get()
            val stale = currentMetadata.filterValues { it.pluginId == info.id }.keys - canonical
            val record = metadata?.copy(pluginId = info.id) ?: LnInstalledPluginMetadata(pluginId = info.id)
            prefs.installedPluginUrls().set(prefs.installedPluginUrls().get() - stale + canonical)
            prefs.installedPluginMetadata().set(currentMetadata - stale + (canonical to record))
            stale
        }
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
        if (prefs.pluginsNeedRevalidation().get() && !revalidateInstalledAgainstReposLocked()) {
            emptyList()
        } else {
            loadedUrls.clear()
            loadUrlsLocked(prefs.installedPluginUrls().get())
        }
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
    private suspend fun rememberSeenSources(sources: List<LnPluginSource>) {
        if (sources.isEmpty()) return
        registryMutex.withLock {
            val current = prefs.seenNovelSources().get()
            val updated = current + sources.associate {
                it.id to LnSourceIdentity(name = it.name, iconUrl = it.iconUrl, lang = it.lang)
            }
            if (updated != current) prefs.seenNovelSources().set(updated)
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
 * Registry-emitted URLs leave reserved path characters like `[` and `]` literal, where pasted URLs had
 * them percent-encoded. Both fetch fine, but `entry.url in installedPluginUrls` is exact string
 * equality, so the registry form missed against a stored encoded one. Forcing `[` and `]` to their
 * percent forms collapses the only mismatch observed.
 */
fun canonicalizePluginUrl(url: String): String =
    url.replace("[", "%5B").replace("]", "%5D")
