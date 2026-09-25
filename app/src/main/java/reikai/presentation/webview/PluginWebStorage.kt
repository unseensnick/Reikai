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
import reikai.novel.host.LnPluginHost
import reikai.novel.host.WEB_STORAGE_SCRIPT
import reikai.novel.host.WebStorageSnapshot
import reikai.novel.host.parseWebStorage
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO

/**
 * What the in-app browser runs after each page load so a light-novel plugin can read the site's storage,
 * as LNReader's WebView does: the latest page's storage is kept for [pluginId], or for [novelSourceId]
 * when that is a plugin, once the browser closes. A no-op for any other page.
 */
@Composable
fun rememberPluginStorageCapture(pluginId: String?, novelSourceId: String?): (WebView) -> Unit {
    if (pluginId == null && novelSourceId == null) return {}
    val model = assistedMetroViewModel<PluginWebStorageViewModel, PluginWebStorageViewModel.Factory> {
        create(pluginId, novelSourceId)
    }
    return remember(model) {
        { webView: WebView ->
            webView.evaluateJavascript(WEB_STORAGE_SCRIPT) { model.onPageStorage(parseWebStorage(it)) }
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
    private var target: String? = pluginId
    private var latest: WebStorageSnapshot? = null

    init {
        if (pluginId == null && novelSourceId != null) {
            viewModelScope.launchIO {
                target = webStoragePluginId(null, novelSourceId, sourceManager.get(novelSourceId))
            }
        }
    }

    fun onPageStorage(snapshot: WebStorageSnapshot?) {
        if (snapshot != null) latest = snapshot
    }

    // On clear rather than on dispose: a rotation disposes the browser but keeps this model.
    override fun onCleared() {
        val plugin = target ?: return
        latest?.let { host.storeWebStorage(plugin, it) }
    }
}

/** The plugin a page's storage belongs to: the one named, else the page's source if it is a plugin. */
internal fun webStoragePluginId(pluginId: String?, novelSourceId: String?, source: NovelSource?): String? =
    pluginId ?: novelSourceId?.takeIf { source is LnPluginSource }
