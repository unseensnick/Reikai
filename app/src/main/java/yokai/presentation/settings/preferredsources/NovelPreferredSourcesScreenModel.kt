package yokai.presentation.settings.preferredsources

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.NovelPreferences
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager

/**
 * Novel-side parallel of [PreferredSourcesScreenModel]. The ranking is stored as a slash-joined
 * [NovelSource.id] string in [NovelPreferences.novelPreferredSources]; [yokai.domain.novel.NovelChapterAggregation]
 * reads it to pick the trunk of a merged novel chapter list.
 *
 * Source ids are Strings here, so [PreferredSourceItem.key] is the id verbatim (no Long mapping).
 * [NovelSourceManager] only lists sources after the plugin host loads them, so the hosting screen
 * triggers that load; this model simply observes the manager's flow.
 */
class NovelPreferredSourcesScreenModel :
    StateScreenModel<NovelPreferredSourcesScreenModel.State>(State.Loading) {

    private val sourceManager: NovelSourceManager by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()

    private val pref = novelPreferences.novelPreferredSources()

    init {
        screenModelScope.launchIO {
            combine(sourceManager.sources, pref.changes()) { sources, ordered ->
                buildState(sources, ordered)
            }.collectLatest { success -> mutableState.update { success } }
        }
    }

    fun addSource(key: String) = persist { it + key }

    fun removeSource(key: String) = persist { it - key }

    fun moveUp(key: String) = persist { ids ->
        val i = ids.indexOf(key)
        if (i <= 0) ids else ids.toMutableList().also { it[i] = it[i - 1]; it[i - 1] = key }
    }

    fun moveDown(key: String) = persist { ids ->
        val i = ids.indexOf(key)
        if (i < 0 || i >= ids.lastIndex) ids else ids.toMutableList().also { it[i] = it[i + 1]; it[i + 1] = key }
    }

    private fun persist(transform: (List<String>) -> List<String>) {
        screenModelScope.launchIO {
            pref.set(transform(parse(pref.get())).joinToString("/"))
        }
    }

    private fun buildState(sources: List<NovelSource>, ordered: String): State.Success {
        val byId = sources.associateBy { it.id }
        val preferred = parse(ordered).mapNotNull { id -> byId[id]?.toItem() }
        val preferredIds = preferred.mapTo(HashSet()) { it.key }
        val available = sources
            .filterNot { it.id in preferredIds }
            .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
            .map { it.toItem() }
        return State.Success(preferred.toImmutableList(), available.toImmutableList())
    }

    // Slash isn't valid in a plugin id, so a slash-join round-trips cleanly; blanks are dropped.
    private fun parse(value: String): List<String> = value.split('/').filter { it.isNotBlank() }

    private fun NovelSource.toItem() = PreferredSourceItem(id, name, lang)

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
