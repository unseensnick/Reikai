package reikai.presentation.library.preferredsources

import androidx.compose.runtime.Immutable

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
