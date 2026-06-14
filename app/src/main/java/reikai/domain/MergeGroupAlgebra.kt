package reikai.domain

/**
 * Pure set-algebra for pref-based merge groups, shared by the manga ([reikai.domain.manga.MangaMergeManager])
 * and novel ([reikai.domain.novel.NovelMergeManager]) merge managers. Operates only on `Long` entity ids
 * and the `Set<String>` pref encodings (a merge entry is a comma-joined sorted id group; an unmerge is a
 * normalized `min,max` pair), so it carries no domain types and is fully unit-testable.
 *
 * Per-type concerns stay in each manager: manga healing validates a group via tracker keys; novels guard
 * same-title auto-merge by author. This object holds only the group-id math both share.
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
