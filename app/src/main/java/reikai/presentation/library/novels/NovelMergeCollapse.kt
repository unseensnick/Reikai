package reikai.presentation.library.novels

import reikai.domain.novel.model.LibraryNovel

/**
 * Collapses persisted merged-novel groups into one rendered entry per group, the novel analogue of
 * [reikai.presentation.library.MangaMergeCollapse]. Pure (reads only its arguments), so it's
 * unit-testable. Buckets by group id (from the merge group tables, supplied as [membership]); the
 * representative is the group's trunk source, the same trunk the merged chapter list uses
 * ([reikai.domain.novel.NovelChapterAggregation]): the per-group override position wins, else the global
 * preferred-novel-source position, else the most chapters, then the lowest id. Ungrouped novels and, when
 * merging is disabled, every novel pass through as their own single-member entry.
 */
object NovelMergeCollapse {

    data class CollapsedNovel(
        val representative: LibraryNovel,
        /** Real novel ids of every group member (size 1 = not merged). */
        val memberIds: List<Long>,
        val totalDownloadCount: Long,
        /**
         * The group's unread count. Starts as the representative's own, and the caller replaces it with
         * the deduplicated cross-source count once that is available. It lives here rather than being
         * written back into [representative] because `LibraryNovel.unreadCount` is derived from
         * `totalChapters - readCount`, and a group can cover more chapters than its representative (the
         * other sources gap-fill), which would make that subtraction negative and break `hasStarted`.
         */
        val unreadCount: Long,
    )

    fun collapse(
        library: List<LibraryNovel>,
        // Novel id -> group id for grouped items; absent for standalone.
        membership: Map<Long, Long>,
        mergingEnabled: Boolean,
        // Group id -> member novel ids in trunk order, only for groups whose per-group source-order
        // override is on. Empty for a group means "no override": rank by the global preferred list instead.
        overrideRankings: Map<Long, List<Long>> = emptyMap(),
        // Global preferred novel-source ids (plugin slugs), highest priority first; the fallback ranking
        // when a group has no override. Empty means ranking falls through to chapter count then id.
        preferredSourceIds: List<String> = emptyList(),
    ): List<CollapsedNovel> {
        if (library.size <= 1 || !mergingEnabled) {
            return library.map { CollapsedNovel(it, listOf(it.novel.id), it.downloadCount, it.unreadCount) }
        }

        val buckets = LinkedHashMap<String, MutableList<LibraryNovel>>()
        val groupIdByKey = HashMap<String, Long>()
        for (item in library) {
            val id = item.novel.id
            val groupId = membership[id]
            val key = groupId?.let { "g$it" } ?: "s$id"
            if (groupId != null) groupIdByKey[key] = groupId
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<CollapsedNovel>()
        for ((key, bucket) in buckets) {
            val overrideOrder = groupIdByKey[key]?.let { overrideRankings[it] }.orEmpty()
            val rep = bucket.minWith(rankComparator(overrideOrder, preferredSourceIds))
            // The merged entry sorts (LastRead) by the most recent read across all members, not just the
            // representative's own, so reading any source bubbles the whole group up.
            val representative = if (bucket.size > 1) {
                rep.copy(novel = rep.novel.copy(lastReadAt = bucket.maxOf { it.novel.lastReadAt ?: 0L }))
            } else {
                rep
            }
            result.add(
                CollapsedNovel(
                    representative = representative,
                    memberIds = bucket.map { it.novel.id },
                    totalDownloadCount = bucket.sumOf { it.downloadCount },
                    // The representative's own count until the caller supplies the deduplicated one.
                    // Never a sum: the grouped sources share chapters, so summing double-counts them.
                    unreadCount = representative.unreadCount,
                ),
            )
        }
        return result
    }

    // The trunk order [NovelChapterAggregation.rank] applies, so the library row and the details chapter
    // list lead on the same source. minWith picks the smallest: override position first (0 = trunk), else
    // the global preferred-source position, else the most chapters, then the lowest id. Novel source ids
    // are Strings (plugin slugs). Uses totalChapters as the count, which only breaks a shared-rank tie.
    private fun rankComparator(
        overrideOrder: List<Long>,
        preferredSourceIds: List<String>,
    ): Comparator<LibraryNovel> = compareBy<LibraryNovel> { novel ->
        if (overrideOrder.isNotEmpty()) {
            overrideOrder.indexOf(novel.novel.id).takeIf { it >= 0 } ?: Int.MAX_VALUE
        } else {
            preferredSourceIds.indexOf(novel.novel.source).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    }
        .thenByDescending { it.totalChapters }
        .thenBy { it.novel.id }
}
