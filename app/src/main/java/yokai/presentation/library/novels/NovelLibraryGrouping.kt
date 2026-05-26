package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryGrouping]. Collapses
 * merged-novel groups into a single rendered entry per group. Algorithm and quirks ported
 * verbatim from the manga helper:
 *
 * - Buckets by canonical merge-key (sorted comma-separated IDs from a future
 *   `novelManualMerges` pref) or by lowercase-trimmed title (auto-merge fallback).
 * - Each bucket is split into subgroups where no `novelManualUnmerges` pair exists between
 *   members; greedy placement into the first compatible subgroup.
 * - For each multi-member subgroup, the primary is chosen by
 *   `maxBy(totalChapters).thenBy(dateAdded)` and stamped with `relatedNovelIds` containing
 *   every ID in the subgroup.
 *
 * Reads raw `Set<String>` for merges / unmerges rather than pref accessors so the screen model
 * (7E) can wire the pref reads itself. The actual `novelManualMerges` / `novelManualUnmerges`
 * / `novelAutoMergeSameTitle` keys land in 7D (C23) — this helper compiles and is testable
 * without them.
 *
 * Pure function: reads only its arguments. No Injekt, no preferences, no DB.
 */
object NovelLibraryGrouping {

    fun collapse(
        library: Map<NovelCategory, List<LibraryItem.Novel>>,
        manualMerges: Set<String>,
        manualUnmerges: Set<String>,
        autoMergeSameTitle: Boolean = true,
    ): Map<NovelCategory, List<LibraryItem.Novel>> {
        if (library.isEmpty()) return library
        val mergeKey = parseMergeKeys(manualMerges)
        val unmergedPairs = parseUnmergedPairs(manualUnmerges)
        return library.mapValues { (_, items) ->
            collapseCategory(items, mergeKey, unmergedPairs, autoMergeSameTitle)
        }
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

    private fun collapseCategory(
        items: List<LibraryItem.Novel>,
        mergeKey: Map<Long, String>,
        unmergedPairs: Set<Pair<Long, Long>>,
        autoMergeSameTitle: Boolean,
    ): List<LibraryItem.Novel> {
        if (items.size <= 1) return items

        val buckets = LinkedHashMap<String, MutableList<LibraryItem.Novel>>()
        for (item in items) {
            val id = item.libraryNovel.novel.id ?: continue
            val key = mergeKey[id]
                ?: if (autoMergeSameTitle) item.libraryNovel.novel.title.lowercase().trim()
                else "id:$id"
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<LibraryItem.Novel>()
        for ((_, bucket) in buckets) {
            if (bucket.size == 1) {
                result.add(bucket.first())
                continue
            }
            val subGroups = splitByUnmergedPairs(bucket, unmergedPairs)
            for (sg in subGroups) {
                if (sg.size == 1) {
                    result.add(sg.first())
                    continue
                }
                val primary = sg.maxWith(
                    compareBy<LibraryItem.Novel> { it.libraryNovel.totalChapters }
                        .thenBy { it.libraryNovel.novel.dateAdded },
                )
                val groupIds = sg.mapNotNull { it.libraryNovel.novel.id }.toLongArray()
                result.add(primary.copy(relatedNovelIds = groupIds))
            }
        }
        return result
    }

    private fun splitByUnmergedPairs(
        bucket: List<LibraryItem.Novel>,
        unmergedPairs: Set<Pair<Long, Long>>,
    ): List<MutableList<LibraryItem.Novel>> {
        if (unmergedPairs.isEmpty()) return listOf(bucket.toMutableList())

        val subGroups = mutableListOf<MutableList<LibraryItem.Novel>>()
        for (item in bucket) {
            val id = item.libraryNovel.novel.id
            if (id == null) {
                subGroups.add(mutableListOf(item))
                continue
            }
            val placed = subGroups.firstOrNull { sg ->
                sg.all { existing ->
                    val eid = existing.libraryNovel.novel.id ?: return@all true
                    val pair = if (id < eid) id to eid else eid to id
                    pair !in unmergedPairs
                }
            }
            if (placed != null) {
                placed.add(item)
            } else {
                subGroups.add(mutableListOf(item))
            }
        }
        return subGroups
    }
}
