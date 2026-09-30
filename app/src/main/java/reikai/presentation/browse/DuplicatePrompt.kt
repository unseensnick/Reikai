package reikai.presentation.browse

import reikai.domain.merge.EntryMergeManager
import reikai.presentation.browse.components.EntrySourceLabel

/**
 * What an add's duplicate dialog shows: the library entries that may be the same series, each one's
 * source label keyed by the type's source id [K], the group of every grouped duplicate so a group
 * collapses into one card, and whether to offer joining one. Only [duplicatePrompt] builds it, called by
 * each adder's `findDuplicates`, which every add path asks.
 */
data class DuplicatePrompt<D, K>(
    val duplicates: List<D>,
    val sourceLabels: Map<K, EntrySourceLabel>,
    val groupIdByEntryId: Map<Long, Long>,
    val suggestGroup: Boolean,
)

/** Null with no duplicate, so both types answer "no prompt" the same way. */
suspend fun <D, K> duplicatePrompt(
    duplicates: List<D>,
    entryId: (D) -> Long,
    mergeManager: EntryMergeManager,
    sourceLabels: suspend (List<D>) -> Map<K, EntrySourceLabel>,
): DuplicatePrompt<D, K>? {
    if (duplicates.isEmpty()) return null
    return DuplicatePrompt(
        duplicates = duplicates,
        sourceLabels = sourceLabels(duplicates),
        groupIdByEntryId = mergeManager.groupIdsFor(duplicates.map(entryId)),
        suggestGroup = mergeManager.suggestGroupingOnAdd,
    )
}
