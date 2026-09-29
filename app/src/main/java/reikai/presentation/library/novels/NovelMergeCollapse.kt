package reikai.presentation.library.novels

import reikai.domain.merge.MergedGroupCounts
import reikai.domain.merge.bucketByMergeGroup
import reikai.domain.merge.sourcePriority
import reikai.domain.merge.trunkOrder
import reikai.domain.novel.model.LibraryNovel

/**
 * Collapses persisted merged-novel groups into one rendered entry per group, the novel analogue of
 * [reikai.presentation.library.MangaMergeCollapse]. Pure; the caller supplies [membership]. The
 * representative is the group's trunk, the same one the merged chapter list uses
 * ([reikai.domain.novel.NovelChapterAggregation]). Ungrouped novels, and all novels when merging is
 * off, pass through as their own single-member entry.
 */
object NovelMergeCollapse {

    data class CollapsedNovel(
        /** A stitched group's representative carries the group's counts, so its unread is the group's. */
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
        // Group id -> member novel ids in trunk order, only for groups whose per-group source-order
        // override is on. Empty for a group means "no override": rank by the global preferred list instead.
        overrideRankings: Map<Long, List<Long>> = emptyMap(),
        // Global preferred novel-source ids (plugin slugs), highest priority first; the fallback ranking
        // when a group has no override. Empty means ranking falls through to chapter count then id.
        preferredSourceIds: List<String> = emptyList(),
        // Group id -> the stored stitch's counts. Absent for a group not stitched yet or with no unit
        // placed, which keeps the representative's own counts, as on manga.
        mergedCountsByGroup: Map<Long, MergedGroupCounts> = emptyMap(),
        // Group id -> merged chapters with a copy on disk. Absent keeps the members' own sum, as on manga.
        mergedDownloadsByGroup: Map<Long, Int> = emptyMap(),
    ): List<CollapsedNovel> {
        return library.bucketByMergeGroup(membership, mergingEnabled) { it.novel.id }.map { bucket ->
            val groupId = bucket.groupId
            val members = bucket.members
            val overrideOrder = groupId?.let { overrideRankings[it] }.orEmpty()
            val rep = members.minWith(rankComparator(overrideOrder, preferredSourceIds))
            // The merged entry sorts (LastRead) by the most recent read across all members, not just the
            // representative's own, so reading any source bubbles the whole group up.
            val representative = if (members.size > 1) {
                rep.copy(lastRead = members.maxOf { it.lastRead })
                    .withGroupCounts(groupId?.let { mergedCountsByGroup[it] })
            } else {
                rep
            }
            CollapsedNovel(
                representative = representative,
                memberIds = members.map { it.novel.id },
                totalDownloadCount = groupId?.let { mergedDownloadsByGroup[it] }?.toLong()
                    ?: members.sumOf { it.downloadCount },
            )
        }
    }

    // The stitch's own total, read and bookmarked counts, never a sum: the grouped sources share
    // chapters. Written together so the unread they imply is the group's; the badge, Started, Bookmarked,
    // the sort and the search all read them off the row. An unstitched group keeps the representative's.
    private fun LibraryNovel.withGroupCounts(counts: MergedGroupCounts?): LibraryNovel =
        counts?.let { copy(totalChapters = it.total, readCount = it.read, bookmarkCount = it.bookmarked) } ?: this

    // The stitch's [trunkOrder]. totalChapters is the novel stitch's own count: novels have no scanlator
    // variants to collapse, so they rank on rows where manga needs the recognized-number count.
    private fun rankComparator(
        overrideOrder: List<Long>,
        preferredSourceIds: List<String>,
    ): Comparator<LibraryNovel> = trunkOrder(
        { sourcePriority(it.novel.id, it.novel.source, preferredSourceIds, overrideOrder) },
        { it.totalChapters },
        { it.novel.id },
    )
}
