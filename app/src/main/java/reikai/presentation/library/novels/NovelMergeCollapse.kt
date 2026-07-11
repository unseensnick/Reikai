package reikai.presentation.library.novels

import reikai.domain.MergeGroupAlgebra
import reikai.domain.novel.model.LibraryNovel

/**
 * Collapses merged-novel groups into one rendered entry per group, the novel analogue of
 * [reikai.presentation.library.MangaMergeCollapse]. Pure (reads only its arguments), so it's
 * unit-testable. Buckets by manual merge-key (a sorted comma-joined id group from `novelManualMerges`)
 * or, for auto-merge, by `title` (+author when the guard is on); each bucket is split where a
 * `novelManualUnmerges` pair forbids grouping; the representative is the most-chapters novel
 * (ties broken by earliest `dateAdded`). The caller summed download count + member ids drive the badge.
 *
 * Source-icon rendering on a merged cover is deferred to S8b (novel sources need a coil-loaded icon
 * path the shared MergeBadge lacks); S8a shows the numeric group count, matching the Yokai era.
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
        manualMerges: Set<String>,
        manualUnmerges: Set<String>,
        autoMergeSameTitle: Boolean,
        requireAuthor: Boolean,
    ): List<CollapsedNovel> {
        if (library.size <= 1) {
            return library.map { CollapsedNovel(it, listOf(it.novel.id), it.downloadCount) }
        }
        val mergeKey = MergeGroupAlgebra.parseMergeKeys(manualMerges)
        val unmergedPairs = MergeGroupAlgebra.parseUnmergedPairs(manualUnmerges)

        val buckets = LinkedHashMap<String, MutableList<LibraryNovel>>()
        for (item in library) {
            val id = item.novel.id
            val key = mergeKey[id] ?: autoKey(item, autoMergeSameTitle, requireAuthor) ?: "id:$id"
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<CollapsedNovel>()
        for ((_, bucket) in buckets) {
            for (subGroup in MergeGroupAlgebra.splitByUnmergedPairs(bucket, unmergedPairs) { it.novel.id }) {
                val rep = subGroup.maxWith(
                    compareBy<LibraryNovel> { it.totalChapters }.thenBy { it.novel.dateAdded },
                )
                // The merged entry sorts (LastRead) by the most recent read across all members, not just
                // the representative's own, so reading any source bubbles the whole group up.
                val representative = if (subGroup.size > 1) {
                    rep.copy(novel = rep.novel.copy(lastReadAt = subGroup.maxOf { it.novel.lastReadAt ?: 0L }))
                } else {
                    rep
                }
                result.add(
                    CollapsedNovel(
                        representative = representative,
                        memberIds = subGroup.map { it.novel.id },
                        totalDownloadCount = subGroup.sumOf { it.downloadCount },
                    ),
                )
            }
        }
        return result
    }

    /** Auto-merge bucket key: same title (+author when the guard is on). Null = don't auto-bucket. */
    private fun autoKey(item: LibraryNovel, autoMergeSameTitle: Boolean, requireAuthor: Boolean): String? {
        if (!autoMergeSameTitle) return null
        val title = item.novel.title.trim().lowercase()
        if (title.isEmpty()) return null
        if (!requireAuthor) return "t:$title"
        val author = item.novel.author?.trim()?.lowercase().orEmpty()
        if (author.isEmpty()) return null // can't confirm a match -> stay standalone
        return "t:$title|a:$author"
    }
}
