package reikai.domain.manga

import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

/**
 * Shares trackers across a pref-based merged group: when a tracker lives on one source of a merged
 * series, every other favorited member of the group gets the same binding. Re-ports the Yokai-era
 * sibling-mirroring lost in the Mihon rebase, gated by [ReikaiLibraryPreferences.syncTrackerLinksGrouped].
 *
 * Copy-on-write: each member ends up with its own `manga_sync` row, so the links survive an unmerge
 * (no cleanup needed on split). Only missing trackers are added, an existing binding for a tracker is
 * never overwritten, and a tracker with conflicting remote ids across the group is skipped (the merge
 * healing pass already de-merges genuinely different series, so within a healthy group this is rare).
 * Removal is intentionally not propagated: dropping a tracker only affects the manga it was removed from.
 */
class PropagateTrackerLinks(
    private val preferences: ReikaiLibraryPreferences,
    private val mergeManager: MangaMergeManager,
    private val getManga: GetManga,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
) {

    /** Resolve [seedMangaId]'s group and mirror each shared tracker onto every favorited member. */
    suspend fun fromSeed(seedMangaId: Long) {
        if (!preferences.syncTrackerLinksGrouped.get()) return
        val groupIds = mergeManager.computeRelatedIds(seedMangaId)
        if (groupIds.size < 2) return

        // Don't link a tracker onto a manga that has left the library.
        val members = groupIds.filter { getManga.await(it)?.favorite == true }
        if (members.size < 2) return

        val tracksByManga = members.associateWith { getTracks.await(it) }

        // One canonical track per tracker across the group; skip a tracker whose remote id disagrees
        // between members (different series slipped into the group) rather than guess which is right.
        val canonical = tracksByManga.values.flatten()
            .groupBy { it.trackerId }
            .mapNotNull { (trackerId, tracks) ->
                if (tracks.mapTo(HashSet()) { it.remoteId }.size == 1) trackerId to tracks.first() else null
            }
            .toMap()
        if (canonical.isEmpty()) return

        val toInsert = members.flatMap { memberId ->
            val have = tracksByManga.getValue(memberId).mapTo(HashSet()) { it.trackerId }
            canonical.filterKeys { it !in have }.values.map { it.copy(mangaId = memberId) }
        }
        if (toInsert.isNotEmpty()) insertTrack.awaitAll(toInsert)
    }
}
