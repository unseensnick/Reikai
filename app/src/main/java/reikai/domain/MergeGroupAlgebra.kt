package reikai.domain

/**
 * Pure set-algebra for pref-based merge groups, shared by the manga ([reikai.domain.manga.MangaMergeManager])
 * and novel ([reikai.domain.novel.NovelMergeManager]) merge managers. Operates only on `Long` entity ids
 * and the `Set<String>` pref encodings (a merge entry is a comma-joined sorted id group; an unmerge is a
 * normalized `min,max` pair), so it carries no domain types and is fully unit-testable.
 *
 * Per-type concerns stay in each manager: manga healing validates a group via tracker keys; novels guard
 * same-title auto-merge by author. This object holds the group-id math and the library-collapse
 * bucketing helpers both share.
 */
object MergeGroupAlgebra {

    /** Normalized "min,max" unmerge key for a pair of ids. */
    fun unmergeKey(a: Long, b: Long): String = if (a < b) "$a,$b" else "$b,$a"

    /**
     * The group ids for [targetId]: the manual-merge members it belongs to, plus the [sameTitleIds],
     * minus any id explicitly unmerged from it. Always includes [targetId].
     */
    fun computeGroupIds(
        targetId: Long,
        merges: Set<String>,
        sameTitleIds: Set<Long>,
        unmerges: Set<String>,
    ): LongArray {
        val merged = merges.flatMap { entry ->
            val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }
            if (targetId in ids) ids else emptyList()
        }

        return (merged + sameTitleIds + targetId)
            .filter { id -> id == targetId || unmergeKey(targetId, id) !in unmerges }
            .distinct()
            .sorted()
            .toLongArray()
    }

    /**
     * Manually merge [ids] into one group: absorb any existing entry that overlaps these ids (transitive
     * merge) into one clean sorted entry, and drop every pairwise unmerge between the members so they
     * collapse together (even with different titles or auto-merge off). Returns null for < 2 distinct ids.
     */
    fun computeMerge(
        ids: List<Long>,
        merges: Set<String>,
        unmerges: Set<String>,
    ): MergeResult? {
        val base = ids.distinct()
        if (base.size < 2) return null
        val overlapping = merges.filter { entry ->
            entry.split(",").mapNotNull { it.trim().toLongOrNull() }.any { it in base }
        }
        val groupIds = (base + overlapping.flatMap { it.split(",").mapNotNull { s -> s.trim().toLongOrNull() } })
            .distinct()
            .sorted()
        val pairs = buildSet {
            for (i in groupIds.indices) {
                for (j in (i + 1) until groupIds.size) add(unmergeKey(groupIds[i], groupIds[j]))
            }
        }
        return MergeResult(
            newMerges = (merges - overlapping.toSet()) + groupIds.joinToString(","),
            newUnmerges = unmerges - pairs,
        )
    }

    /**
     * Compute the pref rewrites for splitting [targetIds] out of [relatedIds]. Returns null when there
     * is nothing to do (no targets, or no survivors to keep grouped).
     */
    fun computeSplit(
        relatedIds: LongArray,
        targetIds: List<Long>,
        merges: Set<String>,
        unmerges: Set<String>,
    ): SplitResult? {
        if (targetIds.isEmpty()) return null
        val targetSet = targetIds.toSet()
        val others = relatedIds.filter { it !in targetSet }
        if (others.isEmpty()) return null

        val newUnmerges = unmerges.toMutableSet()
        for (target in targetIds) {
            for (other in relatedIds) {
                if (other != target) newUnmerges += unmergeKey(target, other)
            }
        }

        val newMerges = merges.filterNotTo(mutableSetOf()) { entry ->
            entry.split(",").any { it.trim().toLongOrNull() in targetSet }
        }
        if (others.size >= 2) newMerges += others.sorted().joinToString(",")

        return SplitResult(others.toLongArray(), newMerges, newUnmerges)
    }

    /**
     * Fully separate every member of [group]: drop all merge entries that reference any member, and
     * record an unmerge pair for every member combination so nothing (manual or same-title) regroups
     * them. [group] is the complete resolved group, so dropping its entries cannot strand an unrelated id.
     */
    fun computeDissolve(
        group: LongArray,
        merges: Set<String>,
        unmerges: Set<String>,
    ): DissolveResult {
        val members = group.toHashSet()
        val newMerges = merges.filterNotTo(mutableSetOf()) { entry ->
            entry.split(",").any { it.trim().toLongOrNull() in members }
        }
        val sorted = group.sorted()
        val newUnmerges = unmerges.toMutableSet()
        for (i in sorted.indices) {
            for (j in (i + 1) until sorted.size) newUnmerges += unmergeKey(sorted[i], sorted[j])
        }
        return DissolveResult(newMerges, newUnmerges)
    }

    /**
     * Map of member id -> canonical group key (sorted comma-joined ids) for every manual-merge entry of
     * 2+ ids. Used by the library collapse to bucket favorites that share a merge group.
     */
    fun parseMergeKeys(merges: Set<String>): Map<Long, String> {
        if (merges.isEmpty()) return emptyMap()
        val mergeKey = mutableMapOf<Long, String>()
        for (entry in merges) {
            val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }.sorted()
            if (ids.size < 2) continue
            val key = ids.joinToString(",")
            ids.forEach { mergeKey[it] = key }
        }
        return mergeKey
    }

    /** Normalized "min,max" unmerge pairs parsed from the pref set; malformed entries are dropped. */
    fun parseUnmergedPairs(unmerges: Set<String>): Set<Pair<Long, Long>> {
        if (unmerges.isEmpty()) return emptySet()
        return unmerges.mapNotNullTo(HashSet()) { entry ->
            val parts = entry.split(",")
            if (parts.size != 2) return@mapNotNullTo null
            val a = parts[0].trim().toLongOrNull() ?: return@mapNotNullTo null
            val b = parts[1].trim().toLongOrNull() ?: return@mapNotNullTo null
            if (a < b) a to b else b to a
        }
    }

    /**
     * Greedy first-fit split of [bucket] into subgroups containing no [unmergedPairs] pair, used by the
     * library collapse. [id] reads each item's entity id, so this stays free of domain types.
     */
    fun <T> splitByUnmergedPairs(
        bucket: List<T>,
        unmergedPairs: Set<Pair<Long, Long>>,
        id: (T) -> Long,
    ): List<MutableList<T>> {
        if (unmergedPairs.isEmpty()) return listOf(bucket.toMutableList())
        val subGroups = mutableListOf<MutableList<T>>()
        for (item in bucket) {
            val itemId = id(item)
            val placed = subGroups.firstOrNull { subGroup ->
                subGroup.all { existing ->
                    val existingId = id(existing)
                    val pair = if (itemId < existingId) itemId to existingId else existingId to itemId
                    pair !in unmergedPairs
                }
            }
            if (placed != null) placed.add(item) else subGroups.add(mutableListOf(item))
        }
        return subGroups
    }

    class MergeResult(
        val newMerges: Set<String>,
        val newUnmerges: Set<String>,
    )

    class SplitResult(
        val survivors: LongArray,
        val newMerges: Set<String>,
        val newUnmerges: Set<String>,
    )

    class DissolveResult(
        val newMerges: Set<String>,
        val newUnmerges: Set<String>,
    )
}
