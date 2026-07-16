package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.MergeGroupAlgebra
import tachiyomi.domain.source.model.Source

/**
 * Collapses pref-based merge groups in the library so the same series favorited from several
 * sources renders as ONE cover with combined counts. Faithful port of the Yokai-era
 * `MangaLibraryGrouping.collapse`, re-typed onto Mihon's flat pre-grouping [LibraryItem] list.
 *
 * Buckets items by their canonical merge key (sorted comma id group from `mangaManualMerges`) or,
 * when [autoMergeSameTitle] is on, their lowercase-trimmed title. Each bucket is split into
 * subgroups with no `mangaManualUnmerges` pair between members (greedy first-fit). Each multi-member
 * subgroup keeps a single primary (most chapters, then earliest added) stamped with the group ids,
 * summed unread / download counts, and the grouped sources for the badge.
 *
 * Pure: reads only its arguments and the [resolveSource] lambda.
 */
object MangaMergeCollapse {

    fun collapse(
        items: List<LibraryItem>,
        manualMerges: Set<String>,
        manualUnmerges: Set<String>,
        autoMergeSameTitle: Boolean,
        // When false, the group's sources are not resolved and the badge falls back to a count.
        showMergeSourceIcons: Boolean,
        resolveSource: (Long) -> Source,
    ): List<LibraryItem> {
        if (items.size <= 1) return items
        val mergeKey = MergeGroupAlgebra.parseMergeKeys(manualMerges)
        val unmergedPairs = MergeGroupAlgebra.parseUnmergedPairs(manualUnmerges)

        val buckets = LinkedHashMap<String, MutableList<LibraryItem>>()
        for (item in items) {
            val id = item.libraryManga.manga.id
            val key = mergeKey[id]
                ?: if (autoMergeSameTitle) item.libraryManga.manga.title.lowercase().trim() else "id:$id"
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<LibraryItem>()
        for ((_, bucket) in buckets) {
            if (bucket.size == 1) {
                result.add(bucket.first())
                continue
            }
            for (subGroup in MergeGroupAlgebra.splitByUnmergedPairs(bucket, unmergedPairs) {
                it.libraryManga.manga.id
            }) {
                if (subGroup.size == 1) {
                    result.add(subGroup.first())
                } else {
                    result.add(mergePrimary(subGroup, showMergeSourceIcons, resolveSource))
                }
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
