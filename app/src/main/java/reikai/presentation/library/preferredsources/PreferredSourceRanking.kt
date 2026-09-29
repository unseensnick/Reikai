package reikai.presentation.library.preferredsources

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference

/** The preferred-sources screen's state, the same for manga and novels. */
sealed interface PreferredSourcesState {
    @Immutable
    data object Loading : PreferredSourcesState

    @Immutable
    data class Success(
        val preferred: List<PreferredSourceItem>,
        val available: List<PreferredSourceItem>,
    ) : PreferredSourcesState
}

/**
 * [ranking] with [key] swapped one place up ([step] -1) or down (+1) past its nearest [visible]
 * neighbour. A ranked source that is no longer installed is hidden from the screen but keeps its
 * place, since the chapter stitchers still read its rank for a merged series' stub member.
 */
fun <K> moveRanked(ranking: List<K>, key: K, visible: Set<K>, step: Int): List<K> {
    val from = ranking.indexOf(key)
    if (from < 0) return ranking
    val to = generateSequence(from + step) { it + step }
        .takeWhile { it in ranking.indices }
        .firstOrNull { ranking[it] in visible }
        ?: return ranking
    return ranking.toMutableList().apply {
        this[from] = this[to]
        this[to] = key
    }
}

/** Splits [ranking] into the installed [sources] it ranks, in order, and the rest by language then name. */
fun preferredSourcesState(ranking: List<String>, sources: List<PreferredSourceItem>): PreferredSourcesState.Success {
    val byKey = sources.associateBy { it.key }
    val preferred = ranking.mapNotNull { byKey[it] }
    val preferredKeys = preferred.mapTo(HashSet()) { it.key }
    val available = sources
        .filterNot { it.key in preferredKeys }
        .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
    return PreferredSourcesState.Success(preferred, available)
}

/** The ranked keys the screen shows, which a move steps between. */
fun PreferredSourcesState.visibleKeys(): Set<String> =
    (this as? PreferredSourcesState.Success)?.preferred?.mapTo(HashSet()) { it.key }.orEmpty()

/**
 * One content type's tab on the preferred-sources screen: its state over the installed [sources] and
 * the stored [ranking], and the edits to that ranking. [K] is the type's source id (a Long for manga, a
 * plugin slug for novels); the screen's key is the id's toString(), which [parseKey] reverses.
 */
class SourceRankingEditor<K>(
    scope: CoroutineScope,
    sources: Flow<List<PreferredSourceItem>>,
    private val ranking: Preference<List<K>>,
    private val parseKey: (String) -> K?,
) {
    val state: StateFlow<PreferredSourcesState> =
        combine(sources, ranking.changes()) { installed, ranked ->
            preferredSourcesState(ranked.map { it.toString() }, installed)
        }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, PreferredSourcesState.Loading)

    fun add(key: String) = edit(key) { ranked, id -> ranked + id }

    fun remove(key: String) = edit(key) { ranked, id -> ranked - id }

    fun moveUp(key: String) = move(key, step = -1)

    fun moveDown(key: String) = move(key, step = 1)

    private fun move(key: String, step: Int) {
        val visible = state.value.visibleKeys().mapNotNullTo(HashSet(), parseKey)
        edit(key) { ranked, id -> moveRanked(ranked, id, visible, step) }
    }

    private inline fun edit(key: String, transform: (ranked: List<K>, id: K) -> List<K>) {
        val id = parseKey(key) ?: return
        ranking.set(transform(ranking.get(), id))
    }
}
