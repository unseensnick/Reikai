package yokai.domain.library.taste.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.track.TrackManager
import yokai.domain.library.taste.TrackerLibraryRepository
import yokai.domain.library.taste.model.TrackedEntry

/**
 * Composes Layer A (live JOIN) + Layer B (cached) into a single deduped stream.
 *
 * Layer A handling:
 * - Layer A wins for *fresh* fields (status, score, tags) when (trackerId, remoteId) matches
 *   a Layer B row — the user's local edits aren't in the cache yet but the live JOIN sees them.
 * - For cross-tracker ids (`malId`, `anilistId`), Layer B wins when present: these are
 *   resolved deterministically at fetch time (AniList via `Media.idMal`, Kitsu via the
 *   `mappings` include) and Layer A's `manga_sync` table can't surface them for foreign
 *   trackers. The borrow step below merges Layer B's cross-refs into Layer A entries.
 * - Layer A rows orphaned by the tracker's API (manga_sync row present, tracker doesn't
 *   return it anymore) are dropped — see the layerAFiltered branch.
 *
 * Cross-tracker dedup:
 * - Two-pass over the composed list, once per cross-ref key. Pass 1 dedups by `malId`,
 *   pass 2 by `anilistId`. Each pass collapses entries sharing the same non-null key down
 *   to one, preferring the tracker with the richest tag taxonomy: **AniList > MAL > Kitsu**.
 * - The two-pass approach handles the asymmetric cross-ref coverage: AniList entries always
 *   have `anilistId` (their own remote id) but `malId` may be null for manhwa; Kitsu has
 *   both via mappings or neither; MAL only has `malId`. Pass 2's `anilistId` catches the
 *   "AniList manhwa + Kitsu has its AniList mapping but no MAL mapping" case that pass 1
 *   misses.
 * - Entries with no cross-ref (Kitsu-original titles, true no-cross-ref AniList entries)
 *   pass through untouched.
 */
class GetTrackedEntries(private val repository: TrackerLibraryRepository) {

    suspend fun await(): List<TrackedEntry> {
        val local = repository.getLocalTrackedEntries()
        val cached = repository.getCachedEntries()
        val composed = if (cached.isEmpty()) {
            local
        } else {
            val cachedByKey: Map<Pair<Long, Long>, TrackedEntry> = cached
                .associateBy { it.trackerId to it.remoteId }
            val trackersWithCache: Set<Long> = cached.mapTo(HashSet()) { it.trackerId }

            // Layer A entries fall into three buckets:
            // - Tracker has no Layer B cache yet → keep as-is (Layer A is the only signal we
            //   have, e.g. before a first refresh or while a toggle is off).
            // - Tracker has Layer B cache and Layer A matches by (trackerId, remoteId) → keep
            //   Layer A (for fresh status/score/tags) but borrow Layer B's malId when set.
            // - Tracker has Layer B cache but Layer A row doesn't match anything in it → drop.
            //   These are orphaned manga_sync rows for track entries the tracker API doesn't
            //   return anymore (deleted on the tracker side, custom list, hidden, etc.). The
            //   tracker API is the source of truth when it has data, so the Layer A row would
            //   double-count an entry that's already in Layer B under a different id, or
            //   represent stale data the user no longer has tracked.
            var orphanedLayerA = 0
            val layerAFiltered = local.mapNotNull { entry ->
                if (entry.trackerId !in trackersWithCache) {
                    entry
                } else {
                    val cachedMatch = cachedByKey[entry.trackerId to entry.remoteId]
                    if (cachedMatch != null) {
                        entry.copy(
                            malId = entry.malId ?: cachedMatch.malId,
                            anilistId = entry.anilistId ?: cachedMatch.anilistId,
                        )
                    } else {
                        orphanedLayerA++
                        null
                    }
                }
            }
            if (orphanedLayerA > 0) {
                Logger.d {
                    "GetTrackedEntries: dropped $orphanedLayerA orphaned Layer A entries " +
                        "(manga_sync rows whose tracker API doesn't return the entry anymore)"
                }
            }

            val layerAKeys = layerAFiltered.mapTo(HashSet(layerAFiltered.size)) { it.trackerId to it.remoteId }
            layerAFiltered + cached.filterNot { (it.trackerId to it.remoteId) in layerAKeys }
        }
        return composed.dedupedAcrossTrackers()
    }

    /**
     * Two-pass cross-tracker dedup. First by `malId`, then by `anilistId` on the result.
     * Both passes prefer the tracker with the richest tag taxonomy (AniList > MAL > Kitsu).
     * Entries lacking the relevant cross-ref in a given pass fall through to the next.
     */
    private fun List<TrackedEntry>.dedupedAcrossTrackers(): List<TrackedEntry> {
        return dedupByCrossRef("malId") { it.malId }
            .dedupByCrossRef("anilistId") { it.anilistId }
    }

    private fun List<TrackedEntry>.dedupByCrossRef(
        label: String,
        key: (TrackedEntry) -> Long?,
    ): List<TrackedEntry> {
        if (none { key(it) != null }) return this
        val (withKey, withoutKey) = partition { key(it) != null }
        val deduped = withKey
            .sortedBy { TRACKER_PRIORITY[it.trackerId] ?: Int.MAX_VALUE }
            .distinctBy(key)
        val dropped = withKey.size - deduped.size
        if (dropped > 0) {
            Logger.d {
                "GetTrackedEntries: cross-tracker dedup ($label) dropped $dropped duplicates " +
                    "(${withKey.size} entries had $label → ${deduped.size} unique; " +
                    "${withoutKey.size} entries had no $label and passed through)"
            }
        }
        return deduped + withoutKey
    }

    companion object {
        // Lower = preferred during cross-tracker dedup. AniList's tag taxonomy is the richest
        // (genres + per-tag rank), MAL has only `genres[]`, Kitsu has `categories[]`.
        private val TRACKER_PRIORITY: Map<Long, Int> = mapOf(
            TrackManager.ANILIST to 0,
            TrackManager.MYANIMELIST to 1,
            TrackManager.KITSU to 2,
        )
    }
}
