package reikai.presentation.library.preferredsources

import androidx.compose.runtime.Immutable
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
import reikai.novel.source.NovelSource
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

    val state: StateFlow<NovelPreferredSourcesViewModel.State>
        field = MutableStateFlow<NovelPreferredSourcesViewModel.State>(State.Loading)

    private val pref = preferences.preferredNovelSources

    init {
        viewModelScope.launchIO {
            runCatching { installer.ensureLoaded() }
            combine(sourceManager.sources, pref.changes()) { sources, ordered ->
                buildState(sources, ordered)
            }.collectLatest { success -> state.update { success } }
        }
    }

    fun addSource(key: String) = persist { it + key }

    fun removeSource(key: String) = persist { it - key }

    fun moveUp(key: String) = persist { keys ->
        val i = keys.indexOf(key)
        if (i <= 0) {
            keys
        } else {
            keys.toMutableList().also {
                it[i] = it[i - 1]
                it[i - 1] = key
            }
        }
    }

    fun moveDown(key: String) = persist { keys ->
        val i = keys.indexOf(key)
        if (i < 0 || i >= keys.lastIndex) {
            keys
        } else {
            keys.toMutableList().also {
                it[i] = it[i + 1]
                it[i + 1] = key
            }
        }
    }

    private fun persist(transform: (List<String>) -> List<String>) {
        viewModelScope.launchIO { pref.set(transform(pref.get())) }
    }

    private fun buildState(sources: List<NovelSource>, ordered: List<String>): State.Success {
        val byId = sources.associateBy { it.id }
        val preferred = ordered.mapNotNull { id -> byId[id]?.toItem() }
        val preferredIds = preferred.mapTo(HashSet()) { it.key }
        val available = sources
            .asSequence()
            .filterNot { it.id in preferredIds }
            .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
            .map { it.toItem() }
            .toList()
        return State.Success(preferred, available)
    }

    private fun NovelSource.toItem() = PreferredSourceItem(id, name, lang)

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val preferred: List<PreferredSourceItem>,
            val available: List<PreferredSourceItem>,
        ) : State
    }
}
