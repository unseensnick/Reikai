package reikai.domain.novel

import reikai.domain.MergeGroupAlgebra
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.model.Novel

/**
 * Pref-based novel merge, the novel analogue of [reikai.domain.manga.MangaMergeManager]. Uses the shared
 * [MergeGroupAlgebra] for the group-id math; the only novel-specific rule is the same-title auto-merge
 * AUTHOR GUARD ([ReikaiLibraryPreferences.novelAutoMergeRequireAuthor]): when on, same-title novels
 * auto-group only if their normalized authors also match and are non-blank. The guard is re-applied on
 * every resolution, so a metadata refresh that diverges an author drops the member (metadata healing).
 * Manual merges are never author-filtered. No tracker healing (novel tracking is deferred).
 *
 * Holds no per-screen state; callers keep their own group ids and pass them in. `removeFromGroup` /
 * `availableSources` (the details Manage-Sources path) land with the details UI in S8b.
 */
class NovelMergeManager(
    private val preferences: ReikaiLibraryPreferences,
    private val novelRepository: NovelRepository,
) {

    /**
     * Group ids for [targetId]: the manual-merge members it belongs to, plus same-title favorites
     * (subject to the author guard), minus any explicit unmerge. Sorted; the caller decides whether it
     * changed. (Consumed by the novel details screen in S8b.)
     */
    suspend fun computeRelatedNovelIds(targetId: Long, title: String, author: String?): LongArray {
        val targetTitle = title.trim().lowercase()
        if (targetTitle.isEmpty()) return longArrayOf(targetId)
        val sameTitle = sameTitleIds(novelRepository.getFavorites(), targetTitle, author)
        return MergeGroupAlgebra.computeGroupIds(
            targetId,
            preferences.novelManualMerges.get(),
            sameTitle,
            preferences.novelManualUnmerges.get(),
        )
    }

    /**
     * Manually merge [ids] into one group: add a merge entry and drop any unmerge pair between them so
     * they collapse together (even with different titles or auto-merge off). No-op for < 2 ids.
     */
    fun mergeNovels(ids: List<Long>) {
        val base = ids.distinct()
        if (base.size < 2) return
        val merges = preferences.novelManualMerges.get()
        // Absorb any overlapping entry (transitive merge) so a re-merge yields one clean entry.
        val overlapping = merges.filter { entry ->
            entry.split(",").mapNotNull { it.trim().toLongOrNull() }.any { it in base }
        }
        val groupIds = (base + overlapping.flatMap { it.split(",").mapNotNull { s -> s.trim().toLongOrNull() } })
            .distinct()
            .sorted()
        preferences.novelManualMerges.set((merges - overlapping.toSet()) + groupIds.joinToString(","))

        val pairs = buildSet {
            for (i in groupIds.indices) {
                for (j in (i + 1) until groupIds.size) add("${groupIds[i]},${groupIds[j]}")
            }
        }
        preferences.novelManualUnmerges.set(preferences.novelManualUnmerges.get() - pairs)
    }

    /**
     * Fully dissolve the merge group of each of [targetIds] (the library bulk "Unmerge"): every member
     * is separated in one pass, including same-title auto-grouped members, so nothing regroups on the
     * next resolution. Targets not in a group are skipped.
     */
    suspend fun unmergeNovels(targetIds: List<Long>) {
        val targets = targetIds.distinct()
        if (targets.isEmpty()) return
        val autoSameTitle = preferences.novelAutoMergeSameTitle.get()
        val favorites = if (autoSameTitle) novelRepository.getFavorites() else emptyList()

        for (target in targets) {
            val merges = preferences.novelManualMerges.get()
            val unmerges = preferences.novelManualUnmerges.get()
            val sameTitle = if (autoSameTitle) {
                val t = favorites.firstOrNull { it.id == target }
                sameTitleIds(favorites, t?.title?.trim()?.lowercase().orEmpty(), t?.author)
            } else {
                emptySet()
            }
            val group = MergeGroupAlgebra.computeGroupIds(target, merges, sameTitle, unmerges)
            if (group.size <= 1) continue
            val result = MergeGroupAlgebra.computeDissolve(group, merges, unmerges)
            preferences.novelManualMerges.set(result.newMerges)
            preferences.novelManualUnmerges.set(result.newUnmerges)
        }
    }

    /** Clear every manual merge entry. Same-title auto-grouping (when on) is left untouched. */
    fun clearManualMerges() {
        preferences.novelManualMerges.set(emptySet())
    }

    /**
     * Separate every currently merged novel, including same-title auto-groups: clear the manual entries
     * and record an unmerge pair for each same-title (author-guarded) duplicate among favorites so
     * auto-grouping stops re-joining them. Newly added favorites still auto-group on first sight.
     */
    suspend fun clearAllMergesIncludingAuto() {
        val requireAuthor = preferences.novelAutoMergeRequireAuthor.get()
        val byKey = HashMap<String, MutableList<Long>>()
        for (novel in novelRepository.getFavorites()) {
            val title = novel.title.trim().lowercase()
            if (title.isEmpty()) continue
            // Bucket by the same key auto-merge groups on, so only novels that WOULD auto-group get pinned.
            if (requireAuthor && normalizeAuthor(novel.author).isEmpty()) continue
            val key = if (requireAuthor) "$title|${normalizeAuthor(novel.author)}" else title
            byKey.getOrPut(key) { mutableListOf() } += novel.id
        }
        val newUnmerges = buildSet {
            for ((_, ids) in byKey) {
                if (ids.size < 2) continue
                val sorted = ids.sorted()
                for (i in sorted.indices) {
                    for (j in (i + 1) until sorted.size) add("${sorted[i]},${sorted[j]}")
                }
            }
        }
        preferences.novelManualMerges.set(emptySet())
        preferences.novelManualUnmerges.set(preferences.novelManualUnmerges.get() + newUnmerges)
    }

    /** Favorited novel ids sharing [title] (already lowercased/trimmed), filtered by the author guard. */
    private fun sameTitleIds(favorites: List<Novel>, title: String, author: String?): Set<Long> {
        if (title.isEmpty() || !preferences.novelAutoMergeSameTitle.get()) return emptySet()
        val requireAuthor = preferences.novelAutoMergeRequireAuthor.get()
        val targetAuthor = normalizeAuthor(author)
        // Guard on: a blank target author can't be confirmed to match, so it never auto-groups.
        if (requireAuthor && targetAuthor.isEmpty()) return emptySet()
        return favorites.asSequence()
            .filter { it.title.trim().equals(title, ignoreCase = true) }
            .filter { !requireAuthor || normalizeAuthor(it.author) == targetAuthor }
            .map { it.id }
            .toSet()
    }

    private fun normalizeAuthor(author: String?): String = author?.trim()?.lowercase().orEmpty()
}
