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
    ): List<LibraryItem> {
        if (items.size <= 1 || !mergingEnabled) return items

        val buckets = LinkedHashMap<String, MutableList<LibraryItem>>()
        for (item in items) {
            val id = item.libraryManga.manga.id
            val key = membership[id]?.let { "g$it" } ?: "s$id"
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<LibraryItem>()
        for ((_, bucket) in buckets) {
            if (bucket.size == 1) {
                result.add(bucket.first())
            } else {
                result.add(mergePrimary(bucket, showMergeSourceIcons, resolveSource))
            }
        }
        return result
    }

    private fun mergePrimary(
        subGroup: List<LibraryItem>,
        showMergeSourceIcons: Boolean,
        resolveSource: (Long) -> Source,
    ): LibraryItem {
        val primary = subGroup.maxWith(
            compareBy<LibraryItem> { it.libraryManga.totalChapters }
                .thenBy { it.libraryManga.manga.dateAdded },
        )
        return primary.copy(
            // Unread tracks the primary (most-chapters) source rather than summing every source, which
            // double-counts the chapters they share. The primary is effectively the unified list, so
            // this is far closer to the real deduped unread than a sum. Downloads stay summed (each is
            // a real per-source file).
            downloadCount = subGroup.sumOf { it.downloadCount },
            // LastRead sorts by the most recent read across all members, not just the primary's own, so
            // reading any source bubbles the merged entry up.
            libraryManga = primary.libraryManga.copy(lastRead = subGroup.maxOf { it.libraryManga.lastRead }),
            relatedMangaIds = subGroup.map { it.libraryManga.manga.id },
            badges = primary.badges.copy(
                downloadCount = subGroup.sumOf { it.badges.downloadCount },
                mergedSources = if (showMergeSourceIcons) {
                    subGroup.map { resolveSource(it.libraryManga.manga.source) }
                } else {
                    emptyList()
                },
            ),
        )
    }
}
