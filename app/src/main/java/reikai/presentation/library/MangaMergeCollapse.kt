package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.source.model.Source

/**
 * Collapses persisted merge groups in the library so the same series favorited from several sources
 * renders as ONE cover with combined counts. Buckets items by their group id (from the merge group
 * tables); each multi-member bucket keeps a single primary (most chapters, then earliest added) stamped
 * with the group ids, summed download counts, and the grouped sources for the badge. Ungrouped items and,
 * when merging is disabled, every item pass through untouched.
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

    private fun mergePrimary(
        subGroup: List<LibraryItem>,
        showMergeSourceIcons: Boolean,
        resolveSource: (Long) -> Source,
        mergedUnread: Long?,
        showUnreadBadge: Boolean,
    ): LibraryItem {
        val primary = subGroup.maxWith(
            compareBy<LibraryItem> { it.libraryManga.totalChapters }
                .thenBy { it.libraryManga.manga.dateAdded },
        )
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
