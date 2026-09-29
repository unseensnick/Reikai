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

/** Entries that render as one row: a merge group's present members, or one entry's items. */
class MergeBucket<T>(
    /** The group, only when two or more items share it; a group with one item present is not merged. */
    val groupId: Long?,
    val members: List<T>,
)

/**
 * Buckets items into the rows a merged surface draws, in first-appearance order: every item of one
 * merge group together, and every item of one ungrouped entry together. With merging off each item is
 * its own bucket. A group is keyed apart from a standalone entry, since an entry id can equal a group id.
 */
fun <T, K> List<T>.bucketByMergeGroup(
    membership: Map<K, Long>,
    mergingEnabled: Boolean,
    id: (T) -> K,
): List<MergeBucket<T>> {
    if (!mergingEnabled || size <= 1) return map { MergeBucket(null, listOf(it)) }
    val buckets = LinkedHashMap<BucketKey<K>, MutableList<T>>()
    forEach { item ->
        val key = membership[id(item)]?.let { BucketKey.Group(it) } ?: BucketKey.Standalone(id(item))
        buckets.getOrPut(key) { mutableListOf() }.add(item)
    }
    return buckets.map { (key, members) ->
        MergeBucket((key as? BucketKey.Group)?.groupId?.takeIf { members.size > 1 }, members)
    }
}

private sealed interface BucketKey<out K> {
    data class Group(val groupId: Long) : BucketKey<Nothing>
    data class Standalone<K>(val id: K) : BucketKey<K>
}

/**
 * The source entries sitting behind the merged rows of a selection, which is what a removal widened to
 * the whole group reaches. An unmerged row contributes nothing: it is one entry whether that option is
 * on or off, so counting it states a number the option cannot change. [membersOf] answers one row's
 * members, which is per content type; the rule about which rows count is not.
 */
fun groupedSourceIdsOf(ids: List<Long>, membersOf: (Long) -> List<Long>): Set<Long> =
    ids.flatMapTo(HashSet()) { id -> membersOf(id).takeIf { it.size > 1 }.orEmpty() }
