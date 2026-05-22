package yokai.novel.install

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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
 * The installer does not own [LnPluginHost]. The caller (probe screen for now, real UI later)
 * provides a host whose lifecycle it controls.
 */
class LnPluginInstaller(
    private val networkHelper: NetworkHelper,
    private val loader: LnPluginLoader,
    private val manager: NovelSourceManager,
    private val prefs: NovelPreferences,
) {

    suspend fun installFromUrl(host: LnPluginHost, pluginJsUrl: String): LnPluginSource {
        val src = loader.fetchSource(pluginJsUrl, forceRefresh = true)
        val info = host.loadPlugin(scopeIdFromUrl(pluginJsUrl), src)
        val source = LnPluginSource(host, info)
        manager.register(source)
        val current = prefs.installedPluginUrls().get()
        prefs.installedPluginUrls().set(current + pluginJsUrl)
        Logger.i(LN_HOST_TAG) { "installed plugin ${info.id} from $pluginJsUrl" }
        return source
    }

    /**
     * Re-load every URL in [NovelPreferences.installedPluginUrls] into [host] and register the
     * resulting sources with [NovelSourceManager]. Individual failures are logged and skipped
     * so one bad URL doesn't block the rest.
     */
    suspend fun loadInstalled(host: LnPluginHost): List<LnPluginSource> {
        val urls = prefs.installedPluginUrls().get()
        return urls.mapNotNull { url ->
            try {
                val src = loader.fetchSource(url, forceRefresh = false)
                val info = host.loadPlugin(scopeIdFromUrl(url), src)
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
     * Remove a plugin's URL from persistence and unregister its source from the manager. The
     * loaded plugin instance stays in the host's WebView until the host is destroyed; that's
     * fine because the source is no longer reachable through the manager.
     */
    suspend fun uninstall(pluginId: String, pluginJsUrl: String) {
        val current = prefs.installedPluginUrls().get()
        prefs.installedPluginUrls().set(current - pluginJsUrl)
        manager.unregister(pluginId)
        Logger.i(LN_HOST_TAG) { "uninstalled plugin $pluginId (was $pluginJsUrl)" }
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
