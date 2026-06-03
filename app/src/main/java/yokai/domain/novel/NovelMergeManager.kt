package yokai.domain.novel

import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.models.Novel

/**
 * Shared source-grouping (merge / unmerge) operations for the novel details screen, the novel
 * analogue of [eu.kanade.tachiyomi.ui.manga.MangaMergeManager]. Holds no per-screen state: the
 * caller keeps its own `relatedNovelIds` and passes it in.
 *
 * Pref-based grouping only (`novelManualMerges` / `novelManualUnmerges` / `autoMergeSameTitle`),
 * no parent-child merged-source DB model. A merge entry is a comma-joined id group; an unmerge is a
 * normalized `min,max` pair, matching what [yokai.presentation.library.novels.NovelLibraryGrouping]
 * consumes.
 *
 * Simpler than the manga manager: novels have no trackers (so no tracker-based pref healing) and no
 * download/cover cache yet, so removing siblings from the library only unfavorites them.
 */
class NovelMergeManager {
    private val preferences: NovelPreferences by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()

    /**
     * Compute the group ids for [targetId]: union the manual-merge members with same-title
     * favorites (when auto-merge is on), then drop any pair explicitly unmerged. Sorted; the caller
     * decides whether it changed.
     */
    suspend fun computeRelatedNovelIds(targetId: Long, title: String): LongArray {
        val targetTitle = title.lowercase().trim()
        if (targetTitle.isEmpty()) return LongArray(0)

        val merges = preferences.novelManualMerges().get()
        val unmerges = preferences.novelManualUnmerges().get()

        val merged = merges.flatMap { entry ->
            val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }
            if (targetId in ids) ids else emptyList()
        }.toSet()

        val sameTitle = if (preferences.autoMergeSameTitle().get()) {
            novelRepo.getFavorites()
                .asSequence()
                .filter { it.id != null && it.title.trim().equals(targetTitle, ignoreCase = true) }
                .mapNotNull { it.id }
                .toSet()
        } else {
            emptySet()
        }

        return (merged + sameTitle + targetId)
            .filter { id ->
                if (id == targetId) return@filter true
                val pair = if (targetId < id) "$targetId,$id" else "$id,$targetId"
                pair !in unmerges
            }
            .sorted()
            .toLongArray()
    }

    /** The grouped sibling novels for [currentNovelId] (favorited, not unmerged from it). */
    suspend fun availableSources(currentNovelId: Long, relatedNovelIds: LongArray): List<Novel> {
        val unmerges = preferences.novelManualUnmerges().get()
        return relatedNovelIds.filter { otherId ->
            if (otherId == currentNovelId) return@filter true
            val pair = if (currentNovelId < otherId) "$currentNovelId,$otherId" else "$otherId,$currentNovelId"
            pair !in unmerges
        }.mapNotNull { id ->
            novelRepo.getById(id)?.takeIf { it.favorite }
        }
    }

    /**
     * Split [targetIds] out of the group: record each removed id as unmerged from every other group
     * member, drop merge entries mentioning a removed id, and re-add a single entry for the
     * survivors so they stay explicitly grouped. Returns the surviving ids.
     */
    fun removeFromGroup(relatedNovelIds: LongArray, targetIds: List<Long>): LongArray {
        if (targetIds.isEmpty()) return relatedNovelIds
        val targetSet = targetIds.toSet()
        val others = relatedNovelIds.filter { it !in targetSet }
        if (others.isEmpty()) return relatedNovelIds

        val unmerges = preferences.novelManualUnmerges().get().toMutableSet()
        for (targetId in targetIds) {
            for (otherId in relatedNovelIds) {
                if (otherId == targetId) continue
                val pair = if (targetId < otherId) "$targetId,$otherId" else "$otherId,$targetId"
                unmerges.add(pair)
            }
        }
        preferences.novelManualUnmerges().set(unmerges)

        val merges = preferences.novelManualMerges().get().toMutableSet()
        merges.removeAll { entry ->
            entry.split(",").any { it.trim().toLongOrNull() in targetSet }
        }
        if (others.size >= 2) {
            merges.add(others.sorted().joinToString(","))
        }
        preferences.novelManualMerges().set(merges)

        return others.toLongArray()
    }

    /** Unfavorite [targetIds]: the library-removal half of dropping siblings. No tracker / download
     *  cleanup (neither exists for novels yet). */
    suspend fun unfavoriteFromGroup(targetIds: List<Long>) {
        targetIds.forEach { id ->
            val novel = novelRepo.getById(id) ?: return@forEach
            if (novel.favorite) novelRepo.update(novel.copy(favorite = false))
        }
    }
}
