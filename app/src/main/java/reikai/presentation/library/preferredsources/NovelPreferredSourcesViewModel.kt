package reikai.presentation.library.preferredsources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO

/**
 * Light-novel counterpart of [PreferredSourcesViewModel]. Ranks installed novel sources highest
 * priority first; [reikai.domain.novel.NovelChapterAggregation] reads the ranking to pick the trunk of
 * a merged chapter list. Novel source ids are Strings (plugin slugs), so the ranking and the shared
 * [PreferredSourcesContent] key are Strings directly. Sources are resolved from the plugin host, and
 * state rebuilds reactively from the registered sources and the stored ranking.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelPreferredSourcesViewModel(
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val preferences: ReikaiLibraryPreferences,
) : ViewModel() {

    val state: StateFlow<PreferredSourcesState>
        field = MutableStateFlow<PreferredSourcesState>(PreferredSourcesState.Loading)

    private val pref = preferences.preferredNovelSources

    init {
        viewModelScope.launchIO {
            runCatching { installer.ensureLoaded() }
            combine(sourceManager.sources, pref.changes()) { sources, ordered ->
                preferredSourcesState(ordered, sources.map { PreferredSourceItem(it.id, it.name, it.lang) })
            }.collectLatest { success -> state.update { success } }
        }
    }

    fun addSource(key: String) = persist { it + key }

    fun removeSource(key: String) = persist { it - key }

    fun moveUp(key: String) = persist { moveRanked(it, key, state.value.visibleKeys(), step = -1) }

    fun moveDown(key: String) = persist { moveRanked(it, key, state.value.visibleKeys(), step = 1) }

    private fun persist(transform: (List<String>) -> List<String>) {
        viewModelScope.launchIO { pref.set(transform(pref.get())) }
    }
}
