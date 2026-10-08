package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.library.mangaLibraryLead
import reikai.domain.merge.MergedGroupCounts
import reikai.domain.merge.bucketByMergeGroup
import tachiyomi.domain.source.model.Source

/**
 * Collapses persisted merge groups so a series favorited from several sources renders as ONE library
 * cover with combined counts: each multi-member bucket keeps one primary stamped with the group by
 * [stampMergedGroup], the rule the novel collapse stamps by too. Ungrouped items, and all items when
 * merging is off, pass through. The primary is the trunk the merged chapter list uses
 * ([reikai.domain.manga.ChapterAggregation]), so its `isLocal` follows the chosen source: a local trunk
 * locks Download, a remote one does not. Pure; the caller supplies [membership].
 */
object MangaMergeCollapse {

    suspend fun collapse(
        items: List<LibraryItem>,
        // Manga id -> group id for grouped items; absent for standalone.
        membership: Map<Long, Long>,
        mergingEnabled: Boolean,
        // When false, the group's sources are not resolved and the badge falls back to a count.
        showMergeSourceIcons: Boolean,
        resolveSource: suspend (Long) -> Source,
        // Group id -> the stored stitch's counts. Absent for a group not stitched yet or with every unit
        // filtered out, which keeps the primary's own counts, as on the novel side.
        mergedCountsByGroup: Map<Long, MergedGroupCounts> = emptyMap(),
        // Group id -> merged chapters with a copy on disk. Absent keeps the members' own sum, as on novels.
        mergedDownloadsByGroup: Map<Long, Int> = emptyMap(),
        // So a merged count never lights a badge the user turned off.
        badgePrefs: LibraryBadgePrefs,
        // Group id -> member manga ids in trunk order, only for groups whose per-group source-order
        // override is on. Empty for a group means "no override": rank by the global preferred list instead.
        overrideRankings: Map<Long, List<Long>> = emptyMap(),
        // Global preferred-source ids, highest priority first; the fallback ranking when a group has no
        // override. Empty means no preference, so ranking falls through to chapter count then id.
        preferredSourceIds: List<Long> = emptyList(),
        // Manga id -> distinct recognized chapter numbers, the count the stitch ranks its trunk on. An
        // absent manga lists none, and ranks as zero there too.
        recognizedChapterCounts: Map<Long, Long> = emptyMap(),
    ): List<LibraryItem> {
        return items.bucketByMergeGroup(membership, mergingEnabled) { it.libraryManga.manga.id }.map { bucket ->
            if (bucket.members.size == 1) return@map bucket.members.single()
            val groupId = bucket.groupId
            mergePrimary(
                subGroup = bucket.members,
                overrideOrder = groupId?.let { overrideRankings[it] }.orEmpty(),
                preferredSourceIds = preferredSourceIds,
                showMergeSourceIcons = showMergeSourceIcons,
                resolveSource = resolveSource,
                mergedCounts = groupId?.let { mergedCountsByGroup[it] },
                mergedDownloads = groupId?.let { mergedDownloadsByGroup[it] },
                badgePrefs = badgePrefs,
                recognizedChapterCounts = recognizedChapterCounts,
            )
        }
    }

    private suspend fun mergePrimary(
        subGroup: List<LibraryItem>,
        overrideOrder: List<Long>,
        preferredSourceIds: List<Long>,
        showMergeSourceIcons: Boolean,
        resolveSource: suspend (Long) -> Source,
        mergedCounts: MergedGroupCounts?,
        mergedDownloads: Int?,
        badgePrefs: LibraryBadgePrefs,
        recognizedChapterCounts: Map<Long, Long>,
    ): LibraryItem {
        val primary = mangaLibraryLead(subGroup, overrideOrder, preferredSourceIds, recognizedChapterCounts) {
            it.libraryManga.manga
        }
        return primary.stampMergedGroup(
            members = subGroup.map {
                MergedRowMember(
                    it.id,
                    it.libraryManga.manga.source,
                    it.libraryManga.lastRead,
                    it.downloadCount,
                    it.libraryManga.manga.genre,
                )
            },
            counts = mergedCounts,
            mergedDownloads = mergedDownloads,
            badgePrefs = badgePrefs,
            showSourceIcons = showMergeSourceIcons,
            // A manga row carries its source's search terms itself, so any member on that source answers.
            querySource = { source ->
                subGroup.first { it.libraryManga.manga.source == source }.querySource(source.toString())
            },
            sourceBadge = { SourceBadge.Manga(resolveSource(it)) },
        )
    }
}
