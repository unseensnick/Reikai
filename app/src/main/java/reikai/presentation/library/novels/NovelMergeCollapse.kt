package reikai.presentation.library.novels

import reikai.domain.novel.model.LibraryNovel

/**
 * Collapses persisted merged-novel groups into one rendered entry per group, the novel analogue of
 * [reikai.presentation.library.MangaMergeCollapse]. Pure (reads only its arguments), so it's
 * unit-testable. Buckets by group id (from the merge group tables, supplied as [membership]); the
 * representative is the most-chapters novel (ties broken by earliest `dateAdded`). Ungrouped novels and,
 * when merging is disabled, every novel pass through as their own single-member entry.
 */
object NovelMergeCollapse {

    data class CollapsedNovel(
        val representative: LibraryNovel,
        /** Real novel ids of every group member (size 1 = not merged). */
        val memberIds: List<Long>,
        val totalDownloadCount: Long,
    )

    fun collapse(
        library: List<LibraryNovel>,
        // Novel id -> group id for grouped items; absent for standalone.
        membership: Map<Long, Long>,
        mergingEnabled: Boolean,
    ): List<CollapsedNovel> {
        if (library.size <= 1 || !mergingEnabled) {
            return library.map { CollapsedNovel(it, listOf(it.novel.id), it.downloadCount) }
        }

        val buckets = LinkedHashMap<String, MutableList<LibraryNovel>>()
        for (item in library) {
            val id = item.novel.id
            val key = membership[id]?.let { "g$it" } ?: "s$id"
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<CollapsedNovel>()
        for ((_, bucket) in buckets) {
            val rep = bucket.maxWith(
                compareBy<LibraryNovel> { it.totalChapters }.thenBy { it.novel.dateAdded },
            )
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
                ),
            )
        }
        return result
    }
}
