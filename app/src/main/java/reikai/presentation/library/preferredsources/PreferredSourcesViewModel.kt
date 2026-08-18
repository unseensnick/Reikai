package reikai.presentation.library.preferredsources

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceManager

/**
 * Manga "preferred sources" ranking, highest priority first, stored in
 * [ReikaiLibraryPreferences.preferredMangaSources] and read by
 * [reikai.domain.manga.ChapterAggregation] to pick the trunk of a merged chapter list (falling back to
 * most-chapters when empty). The novel counterpart is [NovelPreferredSourcesViewModel]; both render
 * the shared [PreferredSourcesContent] over a String key, so this model stringifies its Long ids at the
 * edge. State is rebuilt reactively from the installed sources and the stored ranking.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class PreferredSourcesViewModel(
    private val sourceManager: SourceManager,
    private val preferences: ReikaiLibraryPreferences,
) : ViewModel() {

    val state: StateFlow<PreferredSourcesViewModel.State>
        field = MutableStateFlow<PreferredSourcesViewModel.State>(State.Loading)

    private val pref = preferences.preferredMangaSources

    init {
        viewModelScope.launchIO {
            combine(sourceManager.sources, pref.changes()) { sources, ordered ->
                buildState(sources.filterIsInstance<CatalogueSource>(), ordered)
            }.collectLatest { success -> state.update { success } }
        }
    }

    // Public API speaks the shared String key; ids are Long internally, so parse at the edge.
    fun addSource(key: String) {
        val id = key.toLongOrNull() ?: return
        persist { it + id }
    }

    fun removeSource(key: String) {
        val id = key.toLongOrNull() ?: return
        persist { it - id }
    }

    fun moveUp(key: String) {
        val id = key.toLongOrNull() ?: return
        persist { ids ->
            val i = ids.indexOf(id)
            if (i <= 0) {
                ids
            } else {
                ids.toMutableList().also {
                    it[i] = it[i - 1]
                    it[i - 1] = id
                }
            }
        }
    }

    fun moveDown(key: String) {
        val id = key.toLongOrNull() ?: return
        persist { ids ->
            val i = ids.indexOf(id)
            if (i < 0 || i >= ids.lastIndex) {
                ids
            } else {
                ids.toMutableList().also {
                    it[i] = it[i + 1]
                    it[i + 1] = id
                }
            }
        }
    }

    /** Reads the stored ranking, applies [transform], writes it back; the pref flow rebuilds state. */
    private fun persist(transform: (List<Long>) -> List<Long>) {
        viewModelScope.launchIO { pref.set(transform(pref.get())) }
    }

    private fun buildState(sources: List<CatalogueSource>, ordered: List<Long>): State.Success {
        val byId = sources.associateBy { it.id }
        // Preferred = ranked ids that resolve to an installed source, kept in ranking order.
        val preferred = ordered.mapNotNull { id -> byId[id]?.toItem() }
        val preferredIds = preferred.mapTo(HashSet()) { it.key }
        // Available = the remaining installed catalogue sources, grouped by language then name.
        val available = sources
            .asSequence()
            .filterNot { it.id.toString() in preferredIds }
            .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
            .map { it.toItem() }
            .toList()
        return State.Success(preferred, available)
    }

    private fun CatalogueSource.toItem() = PreferredSourceItem(id.toString(), name, lang)

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
