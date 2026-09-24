package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.merge.sourcePriority
import reikai.domain.merge.trunkOrder
import tachiyomi.domain.source.model.Source

/**
 * Collapses persisted merge groups so a series favorited from several sources renders as ONE library
 * cover with combined counts: each multi-member bucket keeps one primary stamped with the group ids,
 * summed downloads and the grouped sources. Ungrouped items, and all items when merging is off, pass
 * through. The primary is the trunk the merged chapter list uses ([reikai.domain.manga.ChapterAggregation]),
 * so its `isLocal` follows the chosen source: a local trunk locks Download, a remote one does not.
 * Pure; the caller supplies [membership].
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
        // Group id -> deduplicated unread count. A stitched group always has an entry, zero included,
        // so an absent one has not been stitched and keeps the primary's own count, as on the novel side.
        mergedUnreadByGroup: Map<Long, Long> = emptyMap(),
        // Group id -> merged chapters with a copy on disk. A stitched group always has an entry, zero
        // included, so an absent one has not been stitched and keeps the members' own sum.
        mergedDownloadsByGroup: Map<Long, Int> = emptyMap(),
        // Mirrors the unread-badge preference, so a merged count never lights a badge the user turned off.
        showUnreadBadge: Boolean = true,
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
        if (items.size <= 1 || !mergingEnabled) return items

        val buckets = LinkedHashMap<String, MutableList<LibraryItem>>()
        val groupIdByKey = HashMap<String, Long>()
        for (item in items) {
            val id = item.libraryManga.manga.id
            val groupId = membership[id]
            val key = groupId?.let { "g$it" } ?: "s$id"
            if (groupId != null) groupIdByKey[key] = groupId
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<LibraryItem>()
        for ((key, bucket) in buckets) {
            if (bucket.size == 1) {
                result.add(bucket.first())
            } else {
                val groupId = groupIdByKey[key]
                result.add(
                    mergePrimary(
                        subGroup = bucket,
                        overrideOrder = groupId?.let { overrideRankings[it] }.orEmpty(),
                        preferredSourceIds = preferredSourceIds,
                        showMergeSourceIcons = showMergeSourceIcons,
                        resolveSource = resolveSource,
                        mergedUnread = groupId?.let { mergedUnreadByGroup[it] },
                        mergedDownloads = groupId?.let { mergedDownloadsByGroup[it] },
                        showUnreadBadge = showUnreadBadge,
                        recognizedChapterCounts = recognizedChapterCounts,
                    ),
                )
            }
        }
        return result
    }

    // The stitch's [trunkOrder] over the same distinct recognized-number count it ranks on.
    private fun rankComparator(
        overrideOrder: List<Long>,
        preferredSourceIds: List<Long>,
        recognizedChapterCounts: Map<Long, Long>,
    ): Comparator<LibraryItem> = trunkOrder(
        { sourcePriority(it.libraryManga.manga.id, it.libraryManga.manga.source, preferredSourceIds, overrideOrder) },
        { recognizedChapterCounts[it.libraryManga.manga.id] ?: 0L },
        { it.libraryManga.manga.id },
    )

    private suspend fun mergePrimary(
        subGroup: List<LibraryItem>,
        overrideOrder: List<Long>,
        preferredSourceIds: List<Long>,
        showMergeSourceIcons: Boolean,
        resolveSource: suspend (Long) -> Source,
        mergedUnread: Long?,
        mergedDownloads: Int?,
        showUnreadBadge: Boolean,
        recognizedChapterCounts: Map<Long, Long>,
    ): LibraryItem {
        val primary = subGroup.minWith(rankComparator(overrideOrder, preferredSourceIds, recognizedChapterCounts))
        // The real count is one unit per chapter the group covers, unread only when no source's copy is
        // read (see merged_chapter_unit.sq). Summing the members instead would double-count every
        // chapter they share. Falls back to the primary's own count when the group has not been stitched
        // yet, which under-reports rather than inventing a number.
        val unread = mergedUnread ?: primary.unreadCount
        // Downloads count the same way: one per chapter the group holds, however many of its sources
        // hold it. Null is a group nothing has stitched, where the sum is the only answer available.
        val downloads = mergedDownloads ?: subGroup.sumOf { it.downloadCount }
        return primary.copy(
            downloadCount = downloads,
            unreadCount = unread,
            // LastRead sorts by the most recent read across all members, not just the primary's own, so
            // reading any source bubbles the merged entry up.
            //
            // The merged unread count is deliberately NOT written back into LibraryManga by deriving a
            // readCount from it: a group can cover more chapters than its primary (the others gap-fill),
            // which makes that subtraction negative and silently breaks hasStarted, which the "started"
            // filter reads. The count lives on LibraryItem instead, and the filter and sort read it there.
            libraryManga = primary.libraryManga.copy(lastRead = subGroup.maxOf { it.libraryManga.lastRead }),
            relatedMangaIds = subGroup.map { it.libraryManga.manga.id },
            memberSources = subGroup.map { it.querySource(it.libraryManga.manga.source.toString()) }.distinct(),
            badges = primary.badges.copy(
                // Zero when the badge is off, which is how every member reports it then.
                downloadCount = if (subGroup.any { it.badges.downloadCount > 0 }) downloads else 0,
                unreadCount = if (showUnreadBadge) unread else 0,
                mergedSources = if (showMergeSourceIcons) {
                    subGroup.map { resolveSource(it.libraryManga.manga.source) }
                } else {
                    emptyList()
                },
            ),
        )
    }
}
