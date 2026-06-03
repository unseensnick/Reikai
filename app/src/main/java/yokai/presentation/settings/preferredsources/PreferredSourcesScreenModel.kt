package yokai.presentation.settings.preferredsources

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy

/**
 * Manages the manga global ordered "preferred sources" ranking (Phase 6b). The ranking is stored as
 * a slash-joined source-id string in [PreferencesHelper.preferredSources] (highest priority first);
 * [yokai.domain.chapter.ChapterAggregation] reads it to pick the trunk of a merged chapter list.
 *
 * State is rebuilt reactively from the installed catalogue sources and the stored ranking, so it
 * stays correct when an extension is installed/removed or the ranking changes from elsewhere. Items
 * are emitted as neutral [PreferredSourceItem]s (key = the Long id as a String) so the manga and
 * novel tabs share one list UI; this model maps the String key back to a Long at its edge.
 */
class PreferredSourcesScreenModel :
    StateScreenModel<PreferredSourcesScreenModel.State>(State.Loading) {

    private val sourceManager: SourceManager by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    private val pref = preferences.preferredSources()

    init {
        screenModelScope.launchIO {
            combine(sourceManager.catalogueSources, pref.changes()) { sources, ordered ->
                buildState(sources, ordered)
            }.collectLatest { success -> mutableState.update { success } }
        }
    }

    fun addSource(key: String) = key.toLongOrNull()?.let { id -> persist { it + id } } ?: Unit

    fun removeSource(key: String) = key.toLongOrNull()?.let { id -> persist { it - id } } ?: Unit

    fun moveUp(key: String) = key.toLongOrNull()?.let { id ->
        persist { ids ->
            val i = ids.indexOf(id)
            if (i <= 0) ids else ids.toMutableList().also { it[i] = it[i - 1]; it[i - 1] = id }
        }
    } ?: Unit

    fun moveDown(key: String) = key.toLongOrNull()?.let { id ->
        persist { ids ->
            val i = ids.indexOf(id)
            if (i < 0 || i >= ids.lastIndex) ids else ids.toMutableList().also { it[i] = it[i + 1]; it[i + 1] = id }
        }
    } ?: Unit

    /** Re-reads the stored ranking, applies [transform], writes it back. The pref flow then rebuilds
     *  state, so the screen reflects the change without a manual refresh. */
    private fun persist(transform: (List<Long>) -> List<Long>) {
        screenModelScope.launchIO {
            pref.set(transform(parse(pref.get())).joinToString("/"))
        }
    }

    private fun buildState(sources: List<CatalogueSource>, ordered: String): State.Success {
        val byId = sources.associateBy { it.id }
        // Preferred = ranked ids that resolve to an installed source, kept in ranking order.
        val preferred = parse(ordered).mapNotNull { id -> byId[id]?.toItem() }
        val preferredIds = preferred.mapTo(HashSet()) { it.key }
        // Available = the remaining installed catalogue sources, grouped by language then name.
        val available = sources
            .filterNot { it.id.toString() in preferredIds }
            .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
            .map { it.toItem() }
        return State.Success(preferred.toImmutableList(), available.toImmutableList())
    }

    private fun parse(value: String): List<Long> = value.split('/').mapNotNull(String::toLongOrNull)

    private fun CatalogueSource.toItem() = PreferredSourceItem(id.toString(), name, lang)

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val preferred: ImmutableList<PreferredSourceItem>,
            val available: ImmutableList<PreferredSourceItem>,
        ) : State
    }
}
