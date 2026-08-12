package reikai.domain.merge

/**
 * One item per merge group: the first item whose entry belongs to a group keeps it, later members of
 * that group drop out, and an ungrouped entry always stays.
 *
 * One definition of "count a merged series once", so the surfaces counting titles cannot disagree.
 * Order decides the survivor, so hand in a deterministically ordered list. The key is the caller's:
 * raw entry id per type, [reikai.domain.entry.EntryId] for a mixed feed, whose id spaces overlap.
 */
fun <T, K> List<T>.dedupeByMergeGroup(membership: Map<K, Long>, id: (T) -> K): List<T> {
    if (membership.isEmpty()) return this
    val seenGroups = HashSet<Long>()
    return filter { item -> membership[id(item)]?.let(seenGroups::add) ?: true }
}

/**
 * The source entries sitting behind the merged rows of a selection, which is what a removal widened to
 * the whole group reaches. An unmerged row contributes nothing: it is one entry whether that option is
 * on or off, so counting it states a number the option cannot change. [membersOf] answers one row's
 * members, which is per content type; the rule about which rows count is not.
 */
fun groupedSourceIdsOf(ids: List<Long>, membersOf: (Long) -> List<Long>): Set<Long> =
    ids.flatMapTo(HashSet()) { id -> membersOf(id).takeIf { it.size > 1 }.orEmpty() }
