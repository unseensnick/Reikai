package reikai.presentation.library.preferredsources

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Light-novel counterpart of [PreferredSourcesScreenModel]. Ranks installed novel sources highest
 * priority first; [reikai.domain.novel.NovelChapterAggregation] reads the ranking to pick the trunk
 * of a merged chapter list. Novel source ids are Strings (plugin slugs), so the ranking and the
 * shared [PreferredSourcesContent] key are Strings directly (no Long bridging needed).
 *
 * Sources are resolved from the plugin host (loaded once via the installer); state rebuilds reactively
 * from the registered sources and the stored ranking.
 */
class NovelPreferredSourcesScreenModel(
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val installer: LnPluginInstaller = Injekt.get(),
    private val preferences: ReikaiLibraryPreferences = Injekt.get(),
) : StateScreenModel<NovelPreferredSourcesScreenModel.State>(State.Loading) {

    private val pref = preferences.preferredNovelSources

    init {
        screenModelScope.launchIO {
            runCatching { installer.ensureLoaded() }
            combine(sourceManager.sources, pref.changes()) { sources, ordered ->
                buildState(sources, ordered)
            }.collectLatest { success -> mutableState.update { success } }
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
        screenModelScope.launchIO { pref.set(transform(pref.get())) }
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
