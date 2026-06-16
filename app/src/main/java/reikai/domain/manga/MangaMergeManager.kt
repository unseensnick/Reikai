package reikai.domain.manga

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import reikai.domain.MergeGroupAlgebra
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks

/**
 * Shared source-grouping (merge / unmerge) operations for the manga details screen. Holds no
 * per-screen state: callers keep their own group ids and pass them in.
 *
 * Pref-based grouping only (`mangaManualMerges` / `mangaManualUnmerges` / `autoMergeSameTitle`), no
 * parent-child merged-source DB model. A merge entry is a comma-joined id group; an unmerge is a
 * normalized `min,max` pair. The shared group-id math lives in [reikai.domain.MergeGroupAlgebra]; the
 * tracker-based healing ([computeHealing]) stays here in the [Companion] as a pure, testable function.
 *
 * Removing a sibling from the library (unfavorite + cover/download/track cleanup) is intentionally
 * NOT here: that reuses Mihon's own removal flow at the call site. This class owns only the merge
 * pref state.
 */
class MangaMergeManager(
    private val preferences: ReikaiLibraryPreferences,
    private val getFavorites: GetFavorites,
    private val getTracks: GetTracks,
) {

    /** Resolved group ids for a manga, plus how many suspect siblings the healing pass dropped
     *  (so the caller can surface a one-shot cleanup snackbar). */
    class RelatedIdsResult(val ids: LongArray, val cleanupCount: Int)

    /**
     * Compute the group ids for [targetId]: heal corrupted merge prefs, union the manual-merge
     * members with same-title favorites (only when `autoMergeSameTitle` is on), then drop any pair
     * explicitly unmerged. The id set is sorted; the caller decides whether it changed.
     */
    suspend fun computeRelatedMangaIds(targetId: Long, title: String): RelatedIdsResult {
        val targetTitle = title.lowercase().trim()
        if (targetTitle.isEmpty()) return RelatedIdsResult(longArrayOf(targetId), 0)

        val (merges, unmerges, cleanupCount) = applyMergePrefHealing(targetId)

        val sameTitle = if (preferences.autoMergeSameTitle.get()) {
            getFavorites.await()
                .asSequence()
                .filter { it.title.trim().equals(targetTitle, ignoreCase = true) }
                .map { it.id }
                .toSet()
        } else {
            emptySet()
        }

        return RelatedIdsResult(MergeGroupAlgebra.computeGroupIds(targetId, merges, sameTitle, unmerges), cleanupCount)
    }

    /**
     * Split [targetIds] out of the group: record each removed id as unmerged from every other group
     * member, drop merge entries mentioning a removed id, and re-add a single entry for the
     * survivors so they stay explicitly grouped. Returns the surviving ids (unchanged input on no-op).
     */
    fun removeFromGroup(relatedMangaIds: LongArray, targetIds: List<Long>): LongArray {
        val split = MergeGroupAlgebra.computeSplit(
            relatedIds = relatedMangaIds,
            targetIds = targetIds,
            merges = preferences.mangaManualMerges.get(),
            unmerges = preferences.mangaManualUnmerges.get(),
        ) ?: return relatedMangaIds

        preferences.mangaManualUnmerges.set(split.newUnmerges)
        preferences.mangaManualMerges.set(split.newMerges)
        return split.survivors
    }

    /**
     * Manage-sources split. When [targetIds] leave at least one survivor, split them out and keep the
     * survivors grouped (subset split). When they cover the whole [relatedMangaIds] group, dissolve it
     * entirely so every member becomes standalone, the "split all sources" case that would otherwise
     * be a silent no-op. [relatedMangaIds] is the already-resolved group, so dissolving it directly is
     * complete (its pairwise unmerges also block same-title regrouping). Returns the surviving ids
     * (empty on a full dissolve).
     */
    fun splitOrDissolve(relatedMangaIds: LongArray, targetIds: List<Long>): LongArray {
        if (targetIds.isEmpty()) return relatedMangaIds
        val targetSet = targetIds.toSet()
        val survivesSplit = relatedMangaIds.any { it !in targetSet }
        if (survivesSplit) return removeFromGroup(relatedMangaIds, targetIds)

        val result = MergeGroupAlgebra.computeDissolve(
            relatedMangaIds,
            preferences.mangaManualMerges.get(),
            preferences.mangaManualUnmerges.get(),
        )
        preferences.mangaManualMerges.set(result.newMerges)
        preferences.mangaManualUnmerges.set(result.newUnmerges)
        return longArrayOf()
    }

    /**
     * Walk every merge entry referencing [targetId], pair-check each sibling against the target's
     * tracker key set, and rewrite the prefs to drop suspect siblings. Returns the post-cleanup
     * pref snapshots and the dropped-sibling count.
     */
    private suspend fun applyMergePrefHealing(targetId: Long): Triple<Set<String>, Set<String>, Int> {
        val merges = preferences.mangaManualMerges.get()
        val unmerges = preferences.mangaManualUnmerges.get()
        if (merges.isEmpty()) return Triple(merges, unmerges, 0)

        val relevantSiblings = merges
            .asSequence()
            .map { entry -> entry.split(",").mapNotNull { it.trim().toLongOrNull() } }
            .filter { ids -> targetId in ids }
            .flatten()
            .filterTo(HashSet()) { it != targetId }
        if (relevantSiblings.isEmpty()) return Triple(merges, unmerges, 0)

        // Parallel tracker lookups: one async per (target + unique sibling), awaited together.
        val trackerKeys: Map<Long, Set<Pair<Long, Long>>> = coroutineScope {
            (relevantSiblings + targetId)
                .map { id -> id to async { trackerKeysFor(id) } }
                .associate { (id, deferred) -> id to deferred.await() }
        }

        val result = computeHealing(targetId, merges, unmerges, trackerKeys)
        return if (result.dropped == 0) {
            Triple(merges, unmerges, 0)
        } else {
            preferences.mangaManualMerges.set(result.newMerges)
            preferences.mangaManualUnmerges.set(result.newUnmerges)
            Triple(result.newMerges, result.newUnmerges, result.dropped)
        }
    }

    /** Local tracker (trackerId, remoteId) set for [mangaId]. Empty when the manga isn't tracked. */
    private suspend fun trackerKeysFor(mangaId: Long): Set<Pair<Long, Long>> =
        getTracks.await(mangaId).mapTo(HashSet()) { it.trackerId to it.remoteId }

    /**
     * Manually merge [ids] into one group: add a merge entry and drop any unmerge pair between them
     * so they collapse together (even with different titles or auto-merge off). No-op for < 2 ids.
     */
    fun mergeManga(ids: List<Long>) {
        val result = MergeGroupAlgebra.computeMerge(
            ids,
            preferences.mangaManualMerges.get(),
            preferences.mangaManualUnmerges.get(),
        ) ?: return
        preferences.mangaManualMerges.set(result.newMerges)
        preferences.mangaManualUnmerges.set(result.newUnmerges)
    }

    /**
     * Fully dissolve the merge group of each of [targetIds] (the library bulk "Unmerge"): every
     * member of the group is separated in one pass, so the user does not have to unmerge a group
     * source-by-source. Each target's group is resolved against the CURRENT prefs (so dissolving one
     * group does not act on stale state from a previous iteration), then every pairwise combination
     * of its members is recorded as unmerged and all merge entries referencing the group are dropped.
     * Same-title auto-grouped members are included so they cannot re-merge on the next open. Targets
     * that are not part of a group are skipped.
     */
    suspend fun unmergeManga(targetIds: List<Long>) {
        val targets = targetIds.distinct()
        if (targets.isEmpty()) return
        val autoSameTitle = preferences.autoMergeSameTitle.get()
        val favorites = if (autoSameTitle) getFavorites.await() else emptyList()

        for (target in targets) {
            val merges = preferences.mangaManualMerges.get()
            val unmerges = preferences.mangaManualUnmerges.get()
            val sameTitle = if (autoSameTitle) {
                val title = favorites.firstOrNull { it.id == target }?.title?.trim()?.lowercase().orEmpty()
                if (title.isEmpty()) {
                    emptySet()
                } else {
                    favorites.asSequence()
                        .filter { it.title.trim().equals(title, ignoreCase = true) }
                        .map { it.id }
                        .toSet()
                }
            } else {
                emptySet()
            }

            val group = MergeGroupAlgebra.computeGroupIds(target, merges, sameTitle, unmerges)
            if (group.size <= 1) continue
            val result = MergeGroupAlgebra.computeDissolve(group, merges, unmerges)
            preferences.mangaManualMerges.set(result.newMerges)
            preferences.mangaManualUnmerges.set(result.newUnmerges)
        }
    }

    /**
     * Group key per favorite for display grouping (the Updates group-by-series feature): each merged
     * series' members share one key (their sorted, comma-joined ids), so all sources of a merged manga
     * collapse together. Favorites are passed in so the whole map resolves from one DB read. Pure
     * pref-based grouping: no tracker healing and no pref writes (unlike [computeRelatedMangaIds]).
     */
    fun seriesGroupKeys(favorites: List<Manga>): Map<Long, String> {
        val merges = preferences.mangaManualMerges.get()
        val unmerges = preferences.mangaManualUnmerges.get()
        val autoMerge = preferences.autoMergeSameTitle.get()
        val byTitle = if (autoMerge) favorites.groupBy { it.title.trim().lowercase() } else emptyMap()
        return favorites.associate { manga ->
            val sameTitle = if (autoMerge && manga.title.isNotBlank()) {
                byTitle[manga.title.trim().lowercase()]?.mapTo(HashSet()) { it.id }.orEmpty()
            } else {
                emptySet()
            }
            manga.id to MergeGroupAlgebra.computeGroupIds(manga.id, merges, sameTitle, unmerges).joinToString(",")
        }
    }

    /** Clear every manual merge entry. Same-title auto-grouping (when on) is left untouched. */
    fun clearManualMerges() {
        preferences.mangaManualMerges.set(emptySet())
    }

    /**
     * Separate every currently merged series, including same-title auto-groups: clears the manual
     * merge entries and records an unmerge pair for each same-title duplicate among favorites so
     * auto-grouping stops re-joining them. Newly added favorites still auto-group on first sight.
     */
    suspend fun clearAllMergesIncludingAuto() {
        val byTitle = HashMap<String, MutableList<Long>>()
        for (manga in getFavorites.await()) {
            val key = manga.title.trim().lowercase()
            if (key.isEmpty()) continue
            byTitle.getOrPut(key) { mutableListOf() } += manga.id
        }
        val newUnmerges = buildSet {
            for ((_, ids) in byTitle) {
                if (ids.size < 2) continue
                val sorted = ids.sorted()
                for (i in sorted.indices) {
                    for (j in (i + 1) until sorted.size) add("${sorted[i]},${sorted[j]}")
                }
            }
        }
        preferences.mangaManualMerges.set(emptySet())
        preferences.mangaManualUnmerges.set(preferences.mangaManualUnmerges.get() + newUnmerges)
    }

    class HealResult(
        val newMerges: Set<String>,
        val newUnmerges: Set<String>,
        val dropped: Int,
    )

    companion object {

        /**
         * Pure healing decision. For each merge entry containing [targetId], keep a sibling only if
         * either side is untracked or the two share a tracker key; otherwise drop it and record the
         * unmerge. Entries not mentioning [targetId] pass through untouched.
         */
        fun computeHealing(
            targetId: Long,
            merges: Set<String>,
            unmerges: Set<String>,
            trackerKeysByMangaId: Map<Long, Set<Pair<Long, Long>>>,
        ): HealResult {
            val targetKeys = trackerKeysByMangaId[targetId].orEmpty()
            val cleaned = LinkedHashSet<String>(merges.size)
            val added = mutableSetOf<String>()
            var dropped = 0

            for (entry in merges) {
                val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }
                if (targetId !in ids) {
                    cleaned += entry
                    continue
                }
                val verified = mutableListOf<Long>()
                val suspect = mutableListOf<Long>()
                for (id in ids) {
                    if (id == targetId) {
                        verified += id
                        continue
                    }
                    val siblingKeys = trackerKeysByMangaId[id].orEmpty()
                    val ok = targetKeys.isEmpty() ||
                        siblingKeys.isEmpty() ||
                        targetKeys.any { it in siblingKeys }
                    if (ok) verified += id else { suspect += id; dropped++ }
                }
                for (s in suspect) {
                    for (v in verified) added += MergeGroupAlgebra.unmergeKey(s, v)
                }
                if (verified.size >= 2) cleaned += verified.sorted().joinToString(",")
            }

            return HealResult(cleaned, unmerges + added, dropped)
        }
    }
}
