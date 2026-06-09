package reikai.presentation.library.preferredsources

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Manga "preferred sources" ranking, highest priority first. The ranking is stored in
 * [ReikaiLibraryPreferences.preferredMangaSources]; [reikai.domain.manga.ChapterAggregation] reads
 * it to pick the trunk of a merged chapter list (it falls back to a most-chapters heuristic when the
 * ranking is empty). Light novels get their own tab once that vertical lands (P5), so this is
 * manga-only for now.
 *
 * State is rebuilt reactively from the installed catalogue sources and the stored ranking, so it
 * stays correct when an extension is installed/removed or the ranking changes elsewhere.
 */
class PreferredSourcesScreenModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferences: ReikaiLibraryPreferences = Injekt.get(),
) : StateScreenModel<PreferredSourcesScreenModel.State>(State.Loading) {

    private val pref = preferences.preferredMangaSources

    init {
        screenModelScope.launchIO {
            combine(sourceManager.catalogueSources, pref.changes()) { sources, ordered ->
                buildState(sources, ordered)
            }.collectLatest { success -> mutableState.update { success } }
        }
    }

    fun addSource(id: Long) = persist { it + id }

    fun removeSource(id: Long) = persist { it - id }

    fun moveUp(id: Long) = persist { ids ->
        val i = ids.indexOf(id)
        if (i <= 0) ids else ids.toMutableList().also { it[i] = it[i - 1]; it[i - 1] = id }
    }

    fun moveDown(id: Long) = persist { ids ->
        val i = ids.indexOf(id)
        if (i < 0 || i >= ids.lastIndex) ids else ids.toMutableList().also { it[i] = it[i + 1]; it[i + 1] = id }
    }

    /** Reads the stored ranking, applies [transform], writes it back; the pref flow rebuilds state. */
    private fun persist(transform: (List<Long>) -> List<Long>) {
        screenModelScope.launchIO { pref.set(transform(pref.get())) }
    }

    private fun buildState(sources: List<CatalogueSource>, ordered: List<Long>): State.Success {
        val byId = sources.associateBy { it.id }
        // Preferred = ranked ids that resolve to an installed source, kept in ranking order.
        val preferred = ordered.mapNotNull { id -> byId[id]?.toItem() }
        val preferredIds = preferred.mapTo(HashSet()) { it.id }
        // Available = the remaining installed catalogue sources, grouped by language then name.
        val available = sources
            .asSequence()
            .filterNot { it.id in preferredIds }
            .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
            .map { it.toItem() }
            .toList()
        return State.Success(preferred, available)
    }

    private fun CatalogueSource.toItem() = PreferredSourceItem(id, name, lang)

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

@Immutable
data class PreferredSourceItem(val id: Long, val name: String, val lang: String)
