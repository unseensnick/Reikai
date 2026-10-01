package reikai.presentation.webview

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import reikai.novel.host.LnPluginHost
import reikai.novel.host.WEB_STORAGE_SCRIPT
import reikai.novel.host.WebStorageSnapshot
import reikai.novel.host.parseWebStorage
import reikai.novel.network.isSameSite
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO

/**
 * What the in-app browser runs after each page load so a light-novel plugin can read the site's storage,
 * as LNReader's WebView does: the latest page's storage is kept for [pluginId], or for [novelSourceId]
 * when that is a plugin, once the browser closes, if the plugin asks for it. A no-op for any other page.
 */
@Composable
fun rememberPluginStorageCapture(pluginId: String?, novelSourceId: String?): (WebView) -> Unit {
    if (pluginId == null && novelSourceId == null) return {}
    val model = assistedMetroViewModel<PluginWebStorageViewModel, PluginWebStorageViewModel.Factory> {
        create(pluginId, novelSourceId)
    }
    return remember(model) {
        { webView: WebView ->
            val pageUrl = webView.url
            webView.evaluateJavascript(WEB_STORAGE_SCRIPT) { model.onPageStorage(pageUrl, parseWebStorage(it)) }
        }
    }
}

/** Holds the latest page's storage and hands it to the plugin host when the browser screen goes away. */
@AssistedInject
class PluginWebStorageViewModel(
    @Assisted pluginId: String?,
    @Assisted novelSourceId: String?,
    sourceManager: NovelSourceManager,
    private val host: LnPluginHost,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(pluginId: String?, novelSourceId: String?): PluginWebStorageViewModel
    }

    @Volatile
    private var target: LnPluginSource? = null

    // Each host's newest storage with the page it came from, newest host last. Which host is the
    // plugin's site is known only once its source resolves, so the choice waits for the close.
    private val storageByHost = LinkedHashMap<String, Pair<String, WebStorageSnapshot>>()

    init {
        // A plugin's source id is its plugin id, so both entries resolve the same way.
        val id = pluginId ?: novelSourceId
        if (id != null) viewModelScope.launchIO { target = webStoragePlugin(sourceManager.get(id)) }
    }

    fun onPageStorage(pageUrl: String?, snapshot: WebStorageSnapshot?) {
        val pageHost = pageUrl?.toHttpUrlOrNull()?.host ?: return
        if (snapshot == null) return
        storageByHost.remove(pageHost)
        storageByHost[pageHost] = pageUrl to snapshot
    }

    // On clear rather than on dispose: a rotation disposes the browser but keeps this model.
    override fun onCleared() {
        val plugin = target ?: return
        pluginSiteStorage(plugin.site, storageByHost.values)?.let { host.storeWebStorage(plugin.id, it) }
    }
}

/**
 * The newest storage captured on [site] or a subdomain of it, from (page URL, storage) pairs oldest
 * first. Another site's, such as a sign-in popup's or a link followed off the site, is never the
 * plugin's, which LNReader does not check: it keeps whatever page loaded last.
 */
internal fun pluginSiteStorage(site: String, pages: Collection<Pair<String, WebStorageSnapshot>>): WebStorageSnapshot? =
    pages.lastOrNull { (pageUrl, _) -> isSameSite(pageUrl, site) }?.second

/**
 * The plugin a page's storage is kept for: [source] when it is an installed plugin that declares
 * `webStorageUtilized`, as LNReader keeps it only for such a plugin. Null for anything else.
 */
internal fun webStoragePlugin(source: NovelSource?): LnPluginSource? =
    (source as? LnPluginSource)?.takeIf { it.webStorageUtilized }
