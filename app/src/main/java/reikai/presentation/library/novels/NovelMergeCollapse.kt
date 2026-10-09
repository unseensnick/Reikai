package reikai.presentation.library.novels

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.library.novelLibraryRanking
import reikai.domain.merge.MergedGroupCounts
import reikai.domain.merge.bucketByMergeGroup
import reikai.domain.merge.coverFallbacks
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.asNovelCover
import reikai.presentation.library.LibraryBadgePrefs
import reikai.presentation.library.LibraryQuerySource
import reikai.presentation.library.MergedRowMember
import reikai.presentation.library.SourceBadge
import reikai.presentation.library.stampMergedGroup

/**
 * Collapses persisted merged-novel groups into one rendered entry per group, the novel analogue of
 * [reikai.presentation.library.MangaMergeCollapse]. Pure; the caller supplies [membership]. The
 * representative is the group's trunk, the same one the merged chapter list uses
 * ([reikai.domain.novel.NovelChapterAggregation]). Ungrouped novels, and all novels when merging is
 * off, pass through as their own single-member entry.
 */
object NovelMergeCollapse {

    data class CollapsedNovel(
        /** The group's trunk, carrying its own counts; [toLibraryRow] stamps the group's onto its row. */
        val representative: LibraryNovel,
        /** Every member, the representative included (size 1 = not merged). */
        val members: List<LibraryNovel>,
        /** The stored stitch's counts; null for a group not stitched yet, or a lone novel. */
        val mergedCounts: MergedGroupCounts?,
        /** Merged chapters with a copy on disk; null keeps the members' own sum. */
        val mergedDownloads: Int?,
        /** The other members, best ranked first, whose covers the row falls back to. */
        val coverFallbacks: List<LibraryNovel> = emptyList(),
    ) {
        /** Real novel ids of every group member. */
        val memberIds: List<Long> = members.map { it.novel.id }
    }

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
            val overrideOrder = groupId?.let { overrideRankings[it] }.orEmpty()
            val ranked = novelLibraryRanking(bucket.members, overrideOrder, preferredSourceIds)
            CollapsedNovel(
                representative = ranked.first(),
                members = bucket.members,
                mergedCounts = groupId?.let { mergedCountsByGroup[it] },
                mergedDownloads = groupId?.let { mergedDownloadsByGroup[it] },
                coverFallbacks = coverFallbacks(ranked.first(), ranked) { it.novel.id },
            )
        }
    }
}

/**
 * The library row for a collapsed group: the representative's own, stamped with the group when it has
 * more than one member. The representative's badge and search terms come from the same two resolvers
 * as its members', which take a novel source id.
 */
suspend fun NovelMergeCollapse.CollapsedNovel.toLibraryRow(
    badgePrefs: LibraryBadgePrefs,
    showSourceIcons: Boolean,
    querySource: suspend (String) -> LibraryQuerySource,
    sourceBadge: suspend (String) -> SourceBadge,
): LibraryItem {
    val source = representative.novel.source
    val own = querySource(source)
    val row = representative.toLibraryItem(badgePrefs, own.language.orEmpty(), sourceBadge(source), own.name)
    if (members.size == 1) return row
    return row.stampMergedGroup(
        members = members.map {
            MergedRowMember(it.novel.id, it.novel.source, it.lastRead, it.downloadCount.toInt(), it.novel.genre)
        },
        coverFallbacks = coverFallbacks.map { it.novel.asNovelCover() },
        counts = mergedCounts,
        mergedDownloads = mergedDownloads,
        badgePrefs = badgePrefs,
        showSourceIcons = showSourceIcons,
        querySource = querySource,
        sourceBadge = sourceBadge,
    )
}
