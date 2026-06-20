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
 * Holds no per-screen state; callers keep their own group ids and pass them in.
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
     * Merge-group ids for a novel by id alone (resolves its title/author first), or just [novelId] when
     * it isn't stored or isn't grouped. The single entry point group-aware tracking uses, so the group
     * math lives here rather than being repeated at each call site.
     */
    suspend fun relatedNovelIdsFor(novelId: Long): List<Long> {
        val novel = novelRepository.getById(novelId) ?: return listOf(novelId)
        return computeRelatedNovelIds(novel.id, novel.title, novel.author).toList().ifEmpty { listOf(novelId) }
    }

    /**
     * Manually merge [ids] into one group: add a merge entry and drop any unmerge pair between them so
     * they collapse together (even with different titles or auto-merge off). No-op for < 2 ids.
     */
    fun mergeNovels(ids: List<Long>) {
        val result = MergeGroupAlgebra.computeMerge(
            ids,
            preferences.novelManualMerges.get(),
            preferences.novelManualUnmerges.get(),
        ) ?: return
        preferences.novelManualMerges.set(result.newMerges)
        preferences.novelManualUnmerges.set(result.newUnmerges)
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

    /**
     * Manage-sources subset split: split [targetIds] out of [relatedNovelIds] while keeping the
     * survivors grouped. Returns the surviving ids (the original group when there's nothing to split).
     */
    fun removeFromGroup(relatedNovelIds: LongArray, targetIds: List<Long>): LongArray {
        val split = MergeGroupAlgebra.computeSplit(
            relatedIds = relatedNovelIds,
            targetIds = targetIds,
            merges = preferences.novelManualMerges.get(),
            unmerges = preferences.novelManualUnmerges.get(),
        ) ?: return relatedNovelIds
        preferences.novelManualUnmerges.set(split.newUnmerges)
        preferences.novelManualMerges.set(split.newMerges)
        return split.survivors
    }

    /**
     * Manage-sources split: subset-split when survivors remain, else fully dissolve the whole group
     * (the "remove all"/"split all" case that would otherwise be a silent no-op). [relatedNovelIds]
     * is the already-resolved group, so dissolving it directly is complete. Returns the surviving ids
     * (empty on a full dissolve).
     */
    fun splitOrDissolve(relatedNovelIds: LongArray, targetIds: List<Long>): LongArray {
        if (targetIds.isEmpty()) return relatedNovelIds
        val targetSet = targetIds.toSet()
        val survivesSplit = relatedNovelIds.any { it !in targetSet }
        if (survivesSplit) return removeFromGroup(relatedNovelIds, targetIds)

        val result = MergeGroupAlgebra.computeDissolve(
            relatedNovelIds,
            preferences.novelManualMerges.get(),
            preferences.novelManualUnmerges.get(),
        )
        preferences.novelManualMerges.set(result.newMerges)
        preferences.novelManualUnmerges.set(result.newUnmerges)
        return longArrayOf()
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

    /**
     * Group key per favorite for display grouping (the Updates group-by-series feature): each merged
     * series' members share one key (their sorted, comma-joined ids), so all sources of a merged novel
     * collapse together. Favorites are passed in so the whole map resolves from one DB read; no pref
     * writes. Reuses the same manual-merge + author-guarded same-title + unmerge math as resolution.
     */
    fun seriesGroupKeys(favorites: List<Novel>): Map<Long, String> {
        val merges = preferences.novelManualMerges.get()
        val unmerges = preferences.novelManualUnmerges.get()
        return favorites.associate { novel ->
            val sameTitle = sameTitleIds(favorites, novel.title.trim().lowercase(), novel.author)
            novel.id to MergeGroupAlgebra.computeGroupIds(novel.id, merges, sameTitle, unmerges).joinToString(",")
        }
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
