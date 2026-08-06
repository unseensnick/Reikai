package reikai.domain.merge

/**
 * One item per merge group: the first item whose entry belongs to a group keeps it, later members of
 * that group drop out, and an ungrouped entry always stays.
 *
 * One definition of "count a merged series once", so the surfaces that count titles cannot disagree.
 * Order decides which member survives, so a caller whose per-item values differ between members
 * should hand in a deterministically ordered list.
 */
fun <T> List<T>.dedupeByMergeGroup(membership: Map<Long, Long>, id: (T) -> Long): List<T> {
    if (membership.isEmpty()) return this
    val seenGroups = HashSet<Long>()
    return filter { item -> membership[id(item)]?.let(seenGroups::add) ?: true }
}
