package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Collapses merged-manga groups into a single rendered entry per group. Faithful behavioral
 * port of legacy [eu.kanade.tachiyomi.ui.library.LibraryPresenter.applySourceGrouping] at
 * `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt:943-997`.
 *
 * Buckets each category's items by either the canonical merge-key (sorted comma-separated IDs
 * from `preferences.mangaManualMerges`) or the manga's lowercase-trimmed title (the implicit
 * same-title auto-merge behavior). Each bucket is split into subgroups where no unmerge pair
 * (from `preferences.mangaManualUnmerges`) exists between members; greedy placement into the
 * first compatible subgroup. For each multi-member subgroup, the primary is chosen by
 * `maxBy(totalChapters).thenBy(date_added)` and stamped with `relatedMangaIds` containing
 * every ID in the subgroup; siblings are dropped from the rendered list.
 *
 * Pure function: reads only its arguments. No Injekt, no preferences, no DB.
 */
object MangaLibraryGrouping {

    /**
     * @param library category-keyed map of library items as produced by [MangaLibrarySectioner].
     * @param manualMerges raw set of `"id,id,id"` entries from `preferences.mangaManualMerges`.
     * @param manualUnmerges raw set of `"smallerId,largerId"` entries from
     *   `preferences.mangaManualUnmerges`.
     */
    fun collapse(
        library: Map<Category, List<LibraryItem.Manga>>,
        manualMerges: Set<String>,
        manualUnmerges: Set<String>,
    ): Map<Category, List<LibraryItem.Manga>> {
        if (library.isEmpty()) return library
        val mergeKey = parseMergeKeys(manualMerges)
        val unmergedPairs = parseUnmergedPairs(manualUnmerges)
        return library.mapValues { (_, items) ->
            collapseCategory(items, mergeKey, unmergedPairs)
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
        items: List<LibraryItem.Manga>,
        mergeKey: Map<Long, String>,
        unmergedPairs: Set<Pair<Long, Long>>,
    ): List<LibraryItem.Manga> {
        if (items.size <= 1) return items

        val buckets = LinkedHashMap<String, MutableList<LibraryItem.Manga>>()
        for (item in items) {
            val id = item.libraryManga.manga.id ?: continue
            val key = mergeKey[id] ?: item.libraryManga.manga.title.lowercase().trim()
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<LibraryItem.Manga>()
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
                    compareBy<LibraryItem.Manga> { it.libraryManga.totalChapters }
                        .thenBy { it.libraryManga.manga.date_added },
                )
                val groupIds = sg.mapNotNull { it.libraryManga.manga.id }.toLongArray()
                result.add(primary.copy(relatedMangaIds = groupIds))
            }
        }
        return result
    }

    private fun splitByUnmergedPairs(
        bucket: List<LibraryItem.Manga>,
        unmergedPairs: Set<Pair<Long, Long>>,
    ): List<MutableList<LibraryItem.Manga>> {
        if (unmergedPairs.isEmpty()) return listOf(bucket.toMutableList())

        val subGroups = mutableListOf<MutableList<LibraryItem.Manga>>()
        for (item in bucket) {
            val id = item.libraryManga.manga.id
            if (id == null) {
                subGroups.add(mutableListOf(item))
                continue
            }
            val placed = subGroups.firstOrNull { sg ->
                sg.all { existing ->
                    val eid = existing.libraryManga.manga.id ?: return@all true
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
