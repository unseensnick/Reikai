package reikai.presentation.details

/**
 * The manga whose scanlator filter the details page reads and writes: the chip's source on its own,
 * or every source of a merged series under All, since the unified list shows all their chapters and
 * each source's exclusions only ever hide its own.
 */
fun scanlatorTargets(group: EntryMergeGroupHost.GroupState): List<Long> =
    group.selected?.let(::listOf) ?: group.ids.toList()

/** What the scanlator filter lists, and which of those it shows as hidden. */
data class ScanlatorFilterView(val available: Set<String>, val excluded: Set<String>)

/**
 * A scanlator is listed if any target has it, and shows as hidden only if every target that has it
 * hides it: one source still showing its chapters means the list still shows that scanlator.
 */
fun scanlatorFilterView(
    targets: List<Long>,
    availableById: Map<Long, Set<String>>,
    excludedById: Map<Long, Set<String>>,
): ScanlatorFilterView {
    val available = targets.flatMapTo(mutableSetOf()) { availableById[it].orEmpty() }
    val excluded = available.filterTo(mutableSetOf()) { scanlator ->
        targets.filter { scanlator in availableById[it].orEmpty() }.all { scanlator in excludedById[it].orEmpty() }
    }
    return ScanlatorFilterView(available, excluded)
}

/**
 * Each target's new hidden set after the dialog: its own, plus what was ticked, minus what was
 * unticked. Only the change is applied, so a scanlator one source hid on its own chip is not
 * spread to the others by saving under All. Targets whose set is unchanged are left out.
 */
fun scanlatorWrites(
    targets: List<Long>,
    excludedById: Map<Long, Set<String>>,
    shown: Set<String>,
    chosen: Set<String>,
): Map<Long, Set<String>> {
    val ticked = chosen - shown
    val unticked = shown - chosen
    return targets
        .associateWith { id -> excludedById[id].orEmpty() + ticked - unticked }
        .filter { (id, excluded) -> excluded != excludedById[id].orEmpty() }
}
