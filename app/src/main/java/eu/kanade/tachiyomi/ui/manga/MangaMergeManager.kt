package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.removeCover
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import uy.kohesive.injekt.injectLazy
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.domain.track.interactor.DeleteTrack
import yokai.domain.track.interactor.GetTrack

/**
 * Shared source-grouping (merge / unmerge) operations for the manga details screens, used by both
 * the legacy [MangaDetailsPresenter] and the Compose
 * [yokai.presentation.details.manga.MangaDetailsScreenModel]. Holds no per-screen state: each caller
 * keeps its own `relatedMangaIds` and passes it in.
 *
 * Pref-based grouping only (`mangaManualMerges` / `mangaManualUnmerges` / `autoMergeSameTitle`), no
 * parent-child merged-source DB model. A merge entry is a comma-joined id group; an unmerge is a
 * normalized `min,max` pair.
 */
class MangaMergeManager {
    private val preferences: PreferencesHelper by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val getTrack: GetTrack by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val coverCache: CoverCache by injectLazy()
    private val downloadManager: DownloadManager by injectLazy()
    private val deleteTrack: DeleteTrack by injectLazy()

    /** Resolved group ids for a manga, plus how many suspect siblings the healing pass dropped
     *  (so the legacy controller can surface its one-shot cleanup snackbar). */
    class RelatedIdsResult(val ids: LongArray, val cleanupCount: Int)

    /**
     * Compute the group ids for [targetId]: heal corrupted merge prefs, union the manual-merge
     * members with same-title favorites, then drop any pair explicitly unmerged. The id set is
     * sorted; the caller decides whether it changed.
     */
    suspend fun computeRelatedMangaIds(targetId: Long, title: String): RelatedIdsResult {
        val targetTitle = title.lowercase().trim()
        if (targetTitle.isEmpty()) return RelatedIdsResult(LongArray(0), 0)

        val (mergesPrefs, unmergesPrefs, cleanupCount) = applyMergePrefHealing(targetId)

        val merged = mergesPrefs.flatMap { entry ->
            val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }
            if (targetId in ids) ids else emptyList()
        }.toSet()

        val sameTitle = getManga.awaitFavorites()
            .asSequence()
            .filter { it.id != null && it.title.trim().equals(targetTitle, ignoreCase = true) }
            .mapNotNull { it.id }
            .toSet()

        val filtered = (merged + sameTitle + targetId)
            .filter { id ->
                if (id == targetId) return@filter true
                val pair = if (targetId < id) "$targetId,$id" else "$id,$targetId"
                pair !in unmergesPrefs
            }
            .sorted()
            .toLongArray()

        return RelatedIdsResult(filtered, cleanupCount)
    }

    /**
     * Walk every merge entry referencing [targetId], pair-check each sibling against the target's
     * tracker (sync_id, media_id) set, and rewrite the merges + unmerges prefs to drop suspect
     * siblings. Returns the post-cleanup pref snapshots and the dropped-sibling count.
     *
     * Verification per pair: either side untracked -> verified (no evidence); both tracked with an
     * overlapping key -> verified; both tracked with no overlap -> suspect, dropped.
     */
    private suspend fun applyMergePrefHealing(targetId: Long): Triple<Set<String>, Set<String>, Int> {
        val originalMerges = preferences.mangaManualMerges().get()
        val originalUnmerges = preferences.mangaManualUnmerges().get()
        if (originalMerges.isEmpty()) return Triple(originalMerges, originalUnmerges, 0)

        val parsedEntries = originalMerges.map { entry ->
            val ids = entry.split(",").mapNotNull { it.trim().toLongOrNull() }
            entry to ids
        }
        val relevantEntries = parsedEntries.filter { (_, ids) -> targetId in ids }
        if (relevantEntries.isEmpty()) return Triple(originalMerges, originalUnmerges, 0)

        val uniqueSiblings = relevantEntries
            .flatMap { (_, ids) -> ids }
            .filterTo(HashSet()) { it != targetId }

        // Parallel tracker lookups: one async per (target + unique sibling), awaited together.
        val trackerKeysByMangaId: Map<Long, Set<Pair<Long, Long>>> = coroutineScope {
            val ids = uniqueSiblings + targetId
            ids.map { id -> id to async { trackerKeysFor(id) } }
                .associate { (id, deferred) -> id to deferred.await() }
        }
        val targetKeys = trackerKeysByMangaId.getValue(targetId)

        val cleanedMerges = LinkedHashSet<String>(originalMerges.size)
        val addedUnmerges = mutableSetOf<String>()
        var droppedSiblings = 0

        for ((entry, ids) in parsedEntries) {
            if (targetId !in ids) {
                cleanedMerges += entry
                continue
            }
            val verifiedIds = mutableListOf<Long>()
            val suspectIds = mutableListOf<Long>()
            for (id in ids) {
                if (id == targetId) {
                    verifiedIds += id
                    continue
                }
                val siblingKeys = trackerKeysByMangaId[id].orEmpty()
                val verified = targetKeys.isEmpty() ||
                    siblingKeys.isEmpty() ||
                    targetKeys.any { it in siblingKeys }
                if (verified) {
                    verifiedIds += id
                } else {
                    suspectIds += id
                    droppedSiblings++
                }
            }
            for (suspectId in suspectIds) {
                for (verifiedId in verifiedIds) {
                    val pair = if (suspectId < verifiedId) "$suspectId,$verifiedId" else "$verifiedId,$suspectId"
                    addedUnmerges += pair
                }
            }
            if (verifiedIds.size >= 2) {
                cleanedMerges += verifiedIds.sorted().joinToString(",")
            }
        }

        return if (droppedSiblings == 0) {
            Triple(originalMerges, originalUnmerges, 0)
        } else {
            val newUnmerges = (originalUnmerges + addedUnmerges).toSet()
            preferences.mangaManualMerges().set(cleanedMerges)
            preferences.mangaManualUnmerges().set(newUnmerges)
            Triple(cleanedMerges, newUnmerges, droppedSiblings)
        }
    }

    /** Local tracker (sync_id, media_id) set for [mangaId]. Empty when the manga isn't tracked. */
    private suspend fun trackerKeysFor(mangaId: Long): Set<Pair<Long, Long>> =
        getTrack.awaitAllByMangaId(mangaId)
            .mapTo(HashSet()) { it.sync_id to it.media_id }

    /** The grouped sibling sources for [currentMangaId] (favorited, not unmerged from it). */
    suspend fun availableSources(currentMangaId: Long, relatedMangaIds: LongArray): List<Pair<Long, Source>> {
        val unmerges = preferences.mangaManualUnmerges().get()
        return relatedMangaIds.filter { otherId ->
            if (otherId == currentMangaId) return@filter true
            val pair = if (currentMangaId < otherId) "$currentMangaId,$otherId" else "$otherId,$currentMangaId"
            pair !in unmerges
        }.mapNotNull { id ->
            val m = getManga.awaitById(id) ?: return@mapNotNull null
            if (!m.favorite) return@mapNotNull null
            id to sourceManager.getOrStub(m.source)
        }
    }

    /**
     * Split [targetIds] out of the group: record each removed id as unmerged from every other group
     * member, drop merge entries mentioning a removed id, and re-add a single entry for the
     * survivors so they stay explicitly grouped. Returns the surviving ids.
     */
    fun removeFromGroup(relatedMangaIds: LongArray, targetIds: List<Long>): LongArray {
        if (targetIds.isEmpty()) return relatedMangaIds
        val targetSet = targetIds.toSet()
        val others = relatedMangaIds.filter { it !in targetSet }
        if (others.isEmpty()) return relatedMangaIds

        val unmerges = preferences.mangaManualUnmerges().get().toMutableSet()
        for (targetId in targetIds) {
            for (otherId in relatedMangaIds) {
                if (otherId == targetId) continue
                val pair = if (targetId < otherId) "$targetId,$otherId" else "$otherId,$targetId"
                unmerges.add(pair)
            }
        }
        preferences.mangaManualUnmerges().set(unmerges)

        val merges = preferences.mangaManualMerges().get().toMutableSet()
        merges.removeAll { entry ->
            entry.split(",").any { it.trim().toLongOrNull() in targetSet }
        }
        if (others.size >= 2) {
            merges.add(others.sorted().joinToString(","))
        }
        preferences.mangaManualMerges().set(merges)

        return others.toLongArray()
    }

    /** Unfavorite [targetIds] and invalidate their tracker reconciliation. The immediate, awaited
     *  part of removing siblings from the library. */
    suspend fun unfavoriteAndReconcile(targetIds: List<Long>) {
        val updates = targetIds.mapNotNull { id ->
            val target = getManga.awaitById(id) ?: return@mapNotNull null
            if (!target.favorite) null else MangaUpdate(id = id, favorite = false)
        }
        if (updates.isNotEmpty()) updateManga.awaitAll(updates)
        preferences.invalidateTrackerReconciliationFor(targetIds)
    }

    /** Delete covers, downloads, and tracks for [targetIds]. Meant to run in a non-cancellable
     *  scope so a mid-run back-out still finishes the cleanup. */
    suspend fun cleanupRemoved(targetIds: List<Long>) {
        targetIds.forEach { id ->
            val target = getManga.awaitById(id) ?: return@forEach
            target.removeCover(coverCache)
            downloadManager.deleteManga(target, sourceManager.getOrStub(target.source))
            deleteTrack.awaitForMangaAll(id)
        }
    }
}
