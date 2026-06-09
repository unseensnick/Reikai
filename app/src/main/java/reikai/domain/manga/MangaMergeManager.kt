package reikai.domain.manga

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.track.interactor.GetTracks

/**
 * Shared source-grouping (merge / unmerge) operations for the manga details screen. Holds no
 * per-screen state: callers keep their own group ids and pass them in.
 *
 * Pref-based grouping only (`mangaManualMerges` / `mangaManualUnmerges` / `autoMergeSameTitle`), no
 * parent-child merged-source DB model. A merge entry is a comma-joined id group; an unmerge is a
 * normalized `min,max` pair. The set-algebra is in [Companion] as pure functions so it can be
 * unit-tested without the suspend dependencies.
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

        return RelatedIdsResult(computeGroupIds(targetId, merges, sameTitle, unmerges), cleanupCount)
    }

    /**
     * Split [targetIds] out of the group: record each removed id as unmerged from every other group
     * member, drop merge entries mentioning a removed id, and re-add a single entry for the
     * survivors so they stay explicitly grouped. Returns the surviving ids (unchanged input on no-op).
     */
    fun removeFromGroup(relatedMangaIds: LongArray, targetIds: List<Long>): LongArray {
        val split = computeSplit(
            relatedMangaIds = relatedMangaIds,
            targetIds = targetIds,
            merges = preferences.mangaManualMerges.get(),
            unmerges = preferences.mangaManualUnmerges.get(),
        ) ?: return relatedMangaIds

        preferences.mangaManualUnmerges.set(split.newUnmerges)
        preferences.mangaManualMerges.set(split.newMerges)
        return split.survivors
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
        val base = ids.distinct()
        if (base.size < 2) return
        val merges = preferences.mangaManualMerges.get()
        // Absorb any existing entry that overlaps these ids (transitive merge) and drop it, so a
        // re-merge produces one clean entry instead of leaving stale, conflicting ones behind.
        val overlapping = merges.filter { entry ->
            entry.split(",").mapNotNull { it.trim().toLongOrNull() }.any { it in base }
        }
        val groupIds = (base + overlapping.flatMap { it.split(",").mapNotNull { s -> s.trim().toLongOrNull() } })
            .distinct()
            .sorted()
        preferences.mangaManualMerges.set((merges - overlapping.toSet()) + groupIds.joinToString(","))

        val pairs = buildSet {
            for (i in groupIds.indices) {
                for (j in (i + 1) until groupIds.size) add("${groupIds[i]},${groupIds[j]}")
            }
        }
        preferences.mangaManualUnmerges.set(preferences.mangaManualUnmerges.get() - pairs)
    }

    /**
     * Split each of [targetIds] out of its own merge group (the library bulk "Unmerge"). Each target
     * is recomputed against the CURRENT prefs right before it is split, so unmerging several members
     * of the same group in one pass cannot re-merge a pair that an earlier iteration just broke (a
     * stale group snapshot would re-add the survivors and resurrect the broken pair). Non-merged
     * targets are skipped. Same-title auto-grouped members are included so they are separated too.
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

            val group = computeGroupIds(target, merges, sameTitle, unmerges)
            if (group.size > 1) removeFromGroup(group, listOf(target))
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

    class SplitResult(
        val survivors: LongArray,
        val newMerges: Set<String>,
        val newUnmerges: Set<String>,
    )

    class HealResult(
        val newMerges: Set<String>,
        val newUnmerges: Set<String>,
        val dropped: Int,
    )

    companion object {

        /** Normalized "min,max" unmerge key for a pair of ids. */
        fun unmergeKey(a: Long, b: Long): String = if (a < b) "$a,$b" else "$b,$a"

        /**
         * The group ids for [targetId]: the manual-merge members it belongs to, plus the
         * [sameTitleIds], minus any id explicitly unmerged from it. Always includes [targetId].
         */
        fun computeGroupIds(
            targetId: Long,
            merges: Set<String>,
            sameTitleIds: Set<Long>,
            unmerges: Set<String>,
        ): LongArray {
            val merged = merges.flatMap { entry ->
                val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }
                if (targetId in ids) ids else emptyList()
            }

            return (merged + sameTitleIds + targetId)
                .filter { id -> id == targetId || unmergeKey(targetId, id) !in unmerges }
                .distinct()
                .sorted()
                .toLongArray()
        }

        /**
         * Compute the pref rewrites for splitting [targetIds] out of [relatedMangaIds]. Returns null
         * when there is nothing to do (no targets, or no survivors to keep grouped).
         */
        fun computeSplit(
            relatedMangaIds: LongArray,
            targetIds: List<Long>,
            merges: Set<String>,
            unmerges: Set<String>,
        ): SplitResult? {
            if (targetIds.isEmpty()) return null
            val targetSet = targetIds.toSet()
            val others = relatedMangaIds.filter { it !in targetSet }
            if (others.isEmpty()) return null

            val newUnmerges = unmerges.toMutableSet()
            for (target in targetIds) {
                for (other in relatedMangaIds) {
                    if (other != target) newUnmerges += unmergeKey(target, other)
                }
            }

            val newMerges = merges.filterNotTo(mutableSetOf()) { entry ->
                entry.split(",").any { it.trim().toLongOrNull() in targetSet }
            }
            if (others.size >= 2) newMerges += others.sorted().joinToString(",")

            return SplitResult(others.toLongArray(), newMerges, newUnmerges)
        }

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
                    for (v in verified) added += unmergeKey(s, v)
                }
                if (verified.size >= 2) cleaned += verified.sorted().joinToString(",")
            }

            return HealResult(cleaned, unmerges + added, dropped)
        }
    }
}
