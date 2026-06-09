package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
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
        resolveSource: (Long) -> Source,
    ): List<LibraryItem> {
        if (items.size <= 1) return items
        val mergeKey = parseMergeKeys(manualMerges)
        val unmergedPairs = parseUnmergedPairs(manualUnmerges)

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
            for (subGroup in splitByUnmergedPairs(bucket, unmergedPairs)) {
                if (subGroup.size == 1) {
                    result.add(subGroup.first())
                } else {
                    result.add(mergePrimary(subGroup, resolveSource))
                }
            }
        }
        return result
    }

    private fun mergePrimary(subGroup: List<LibraryItem>, resolveSource: (Long) -> Source): LibraryItem {
        val primary = subGroup.maxWith(
            compareBy<LibraryItem> { it.libraryManga.totalChapters }
                .thenBy { it.libraryManga.manga.dateAdded },
        )
        return primary.copy(
            unreadCount = subGroup.sumOf { it.unreadCount },
            downloadCount = subGroup.sumOf { it.downloadCount },
            relatedMangaIds = subGroup.map { it.libraryManga.manga.id },
            badges = primary.badges.copy(
                // Sum the pref-gated badge counts so the badge stays off when the user disabled it.
                unreadCount = subGroup.sumOf { it.badges.unreadCount },
                downloadCount = subGroup.sumOf { it.badges.downloadCount },
                mergedSources = subGroup.map { resolveSource(it.libraryManga.manga.source) },
            ),
        )
    }

    private fun parseMergeKeys(manualMerges: Set<String>): Map<Long, String> {
        if (manualMerges.isEmpty()) return emptyMap()
        val mergeKey = mutableMapOf<Long, String>()
        for (entry in manualMerges) {
            val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }.sorted()
            if (ids.size < 2) continue
            val key = ids.joinToString(",")
            ids.forEach { mergeKey[it] = key }
        }
        return mergeKey
    }

    private fun parseUnmergedPairs(manualUnmerges: Set<String>): Set<Pair<Long, Long>> {
        if (manualUnmerges.isEmpty()) return emptySet()
        return manualUnmerges.mapNotNullTo(HashSet()) { entry ->
            val parts = entry.split(",")
            if (parts.size != 2) return@mapNotNullTo null
            val a = parts[0].trim().toLongOrNull() ?: return@mapNotNullTo null
            val b = parts[1].trim().toLongOrNull() ?: return@mapNotNullTo null
            if (a < b) a to b else b to a
        }
    }

    private fun splitByUnmergedPairs(
        bucket: List<LibraryItem>,
        unmergedPairs: Set<Pair<Long, Long>>,
    ): List<MutableList<LibraryItem>> {
        if (unmergedPairs.isEmpty()) return listOf(bucket.toMutableList())

        val subGroups = mutableListOf<MutableList<LibraryItem>>()
        for (item in bucket) {
            val id = item.libraryManga.manga.id
            val placed = subGroups.firstOrNull { subGroup ->
                subGroup.all { existing ->
                    val existingId = existing.libraryManga.manga.id
                    val pair = if (id < existingId) id to existingId else existingId to id
                    pair !in unmergedPairs
                }
            }
            if (placed != null) placed.add(item) else subGroups.add(mutableListOf(item))
        }
        return subGroups
    }
}
