package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.source.model.Source

/**
 * Collapses persisted merge groups in the library so the same series favorited from several sources
 * renders as ONE cover with combined counts. Buckets items by their group id (from the merge group
 * tables); each multi-member bucket keeps a single primary (the group's trunk source) stamped with the
 * group ids, summed download counts, and the grouped sources for the badge. Ungrouped items and, when
 * merging is disabled, every item pass through untouched.
 *
 * The primary is the same trunk the merged chapter list uses (see [reikai.domain.manga.ChapterAggregation]),
 * so the library row leads on the same source the details "All" chapter list trunks on: the per-group
 * override position wins, else the global preferred-source position, else the most chapters, then the
 * lowest id. Because the primary now honours the user's chosen trunk, its own `isLocal` (which drives the
 * Local badge and whether Download is offered) correctly reflects that choice: pick a local source as the
 * trunk and Download locks; pick a remote one and it stays available.
 *
 * Pure: reads only its arguments and the [resolveSource] lambda; the caller supplies the [membership] map.
 */
object MangaMergeCollapse {

    fun collapse(
        items: List<LibraryItem>,
        // Manga id -> group id for grouped items; absent for standalone.
        membership: Map<Long, Long>,
        mergingEnabled: Boolean,
        // When false, the group's sources are not resolved and the badge falls back to a count.
        showMergeSourceIcons: Boolean,
        resolveSource: (Long) -> Source,
        // Group id -> deduplicated unread count. A group is ABSENT when everything in it is read, so a
        // missing entry means zero, not "unknown". Empty until the match-key backfill has run, in which
        // case the group keeps the primary's own count rather than reporting a wrong one.
        mergedUnreadByGroup: Map<Long, Long> = emptyMap(),
        // Mirrors the unread-badge preference, so a merged count never lights a badge the user turned off.
        showUnreadBadge: Boolean = true,
        // Group id -> member manga ids in trunk order, only for groups whose per-group source-order
        // override is on. Empty for a group means "no override": rank by the global preferred list instead.
        overrideRankings: Map<Long, List<Long>> = emptyMap(),
        // Global preferred-source ids, highest priority first; the fallback ranking when a group has no
        // override. Empty means no preference, so ranking falls through to chapter count then id.
        preferredSourceIds: List<Long> = emptyList(),
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
                        // Absent group = nothing unread. Null only when the map has no data at all.
                        mergedUnread = if (groupId != null && mergedUnreadByGroup.isNotEmpty()) {
                            mergedUnreadByGroup[groupId] ?: 0L
                        } else {
                            null
                        },
                        showUnreadBadge = showUnreadBadge,
                    ),
                )
            }
        }
        return result
    }

    // The trunk order [ChapterAggregation.rank] applies, so the library row and the details chapter list
    // lead on the same source. minWith picks the smallest: override position first (0 = trunk), else the
    // global preferred-source position, else the source with the most chapters, then the lowest id. Uses
    // the library's own totalChapters as the count (the details path's distinct-recognized count needs
    // chapter rows the library deliberately never loads per emission), which only matters as a tiebreak
    // between members that share a rank.
    private fun rankComparator(
        overrideOrder: List<Long>,
        preferredSourceIds: List<Long>,
    ): Comparator<LibraryItem> = compareBy<LibraryItem> { item ->
        val mangaId = item.libraryManga.manga.id
        if (overrideOrder.isNotEmpty()) {
            overrideOrder.indexOf(mangaId).takeIf { it >= 0 } ?: Int.MAX_VALUE
        } else {
            preferredSourceIds.indexOf(item.libraryManga.manga.source).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    }
        .thenByDescending { it.libraryManga.totalChapters }
        .thenBy { it.libraryManga.manga.id }

    private fun mergePrimary(
        subGroup: List<LibraryItem>,
        overrideOrder: List<Long>,
        preferredSourceIds: List<Long>,
        showMergeSourceIcons: Boolean,
        resolveSource: (Long) -> Source,
        mergedUnread: Long?,
        showUnreadBadge: Boolean,
    ): LibraryItem {
        val primary = subGroup.minWith(rankComparator(overrideOrder, preferredSourceIds))
        // The real count is one unit per chapter the group covers, unread only when no source's copy is
        // read (see chapter_match_key.sq). Summing the members instead would double-count every chapter
        // they share. Falls back to the primary's own count when the identities are not available yet,
        // which under-reports rather than inventing a number.
        val unread = mergedUnread ?: primary.unreadCount
        return primary.copy(
            downloadCount = subGroup.sumOf { it.downloadCount },
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
            badges = primary.badges.copy(
                downloadCount = subGroup.sumOf { it.badges.downloadCount },
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
