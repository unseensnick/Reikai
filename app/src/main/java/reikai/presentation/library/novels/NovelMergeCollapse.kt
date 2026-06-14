package reikai.presentation.library.novels

import reikai.domain.novel.model.LibraryNovel

/**
 * Collapses merged-novel groups into one rendered entry per group, the novel analogue of
 * [reikai.presentation.library.MangaMergeCollapse]. Pure (reads only its arguments), so it's
 * unit-testable. Buckets by manual merge-key (a sorted comma-joined id group from `novelManualMerges`)
 * or, for auto-merge, by `title` (+author when the guard is on); each bucket is split where a
 * `novelManualUnmerges` pair forbids grouping; the representative is the most-chapters novel
 * (ties broken by earliest `dateAdded`). The caller summed download count + member ids drive the badge.
 *
 * Source-icon rendering on a merged cover is deferred to S8b (novel sources need a coil-loaded icon
 * path the shared MergeBadge lacks); S8a shows the numeric group count, matching the Yokai era.
 */
object NovelMergeCollapse {

    data class CollapsedNovel(
        val representative: LibraryNovel,
        /** Real novel ids of every group member (size 1 = not merged). */
        val memberIds: List<Long>,
        val totalDownloadCount: Long,
    )

    fun collapse(
        library: List<LibraryNovel>,
        manualMerges: Set<String>,
        manualUnmerges: Set<String>,
        autoMergeSameTitle: Boolean,
        requireAuthor: Boolean,
    ): List<CollapsedNovel> {
        if (library.size <= 1) {
            return library.map { CollapsedNovel(it, listOf(it.novel.id), it.downloadCount) }
        }
        val mergeKey = parseMergeKeys(manualMerges)
        val unmergedPairs = parseUnmergedPairs(manualUnmerges)

        val buckets = LinkedHashMap<String, MutableList<LibraryNovel>>()
        for (item in library) {
            val id = item.novel.id
            val key = mergeKey[id] ?: autoKey(item, autoMergeSameTitle, requireAuthor) ?: "id:$id"
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<CollapsedNovel>()
        for ((_, bucket) in buckets) {
            for (subGroup in splitByUnmergedPairs(bucket, unmergedPairs)) {
                val rep = subGroup.maxWith(
                    compareBy<LibraryNovel> { it.totalChapters }.thenBy { it.novel.dateAdded },
                )
                result.add(
                    CollapsedNovel(
                        representative = rep,
                        memberIds = subGroup.map { it.novel.id },
                        totalDownloadCount = subGroup.sumOf { it.downloadCount },
                    ),
                )
            }
        }
        return result
    }

    /** Auto-merge bucket key: same title (+author when the guard is on). Null = don't auto-bucket. */
    private fun autoKey(item: LibraryNovel, autoMergeSameTitle: Boolean, requireAuthor: Boolean): String? {
        if (!autoMergeSameTitle) return null
        val title = item.novel.title.trim().lowercase()
        if (title.isEmpty()) return null
        if (!requireAuthor) return "t:$title"
        val author = item.novel.author?.trim()?.lowercase().orEmpty()
        if (author.isEmpty()) return null // can't confirm a match -> stay standalone
        return "t:$title|a:$author"
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
        bucket: List<LibraryNovel>,
        unmergedPairs: Set<Pair<Long, Long>>,
    ): List<MutableList<LibraryNovel>> {
        if (unmergedPairs.isEmpty()) return listOf(bucket.toMutableList())

        val subGroups = mutableListOf<MutableList<LibraryNovel>>()
        for (item in bucket) {
            val id = item.novel.id
            val placed = subGroups.firstOrNull { subGroup ->
                subGroup.all { existing ->
                    val existingId = existing.novel.id
                    val pair = if (id < existingId) id to existingId else existingId to id
                    pair !in unmergedPairs
                }
            }
            if (placed != null) placed.add(item) else subGroups.add(mutableListOf(item))
        }
        return subGroups
    }
}
