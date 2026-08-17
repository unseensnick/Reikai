package reikai.domain.manga

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.track.canonicalTracksPerTracker
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

/**
 * Copies a merged group's trackers onto its members so each keeps the binding once the group is split.
 * Runs just before a split rather than at merge time: while merged, [GetTracksInGroup] already reads
 * the whole group, so an earlier copy would only go stale. Each member ends up with its own
 * `manga_sync` row carrying the group's FURTHEST-READ values, since a member left behind at chapter 5
 * would otherwise push the remote service backwards after the split. A tracker with conflicting remote
 * ids across the group is skipped rather than guessed at.
 */
@Inject
@SingleIn(AppScope::class)
class PropagateTrackerLinks(
    private val preferences: ReikaiLibraryPreferences,
    private val mergeManager: MangaMergeManager,
    private val getManga: GetManga,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
) {

    /** Resolve [seedMangaId]'s group and copy each shared tracker onto every favorited member. */
    suspend fun fromSeed(seedMangaId: Long) = distribute(mergeManager.computeRelatedIds(seedMangaId).toList())

    /** Ensure every favorited member of [groupIds] carries each tracker bound anywhere in the group. */
    suspend fun distribute(groupIds: List<Long>) {
        if (!preferences.syncTrackerLinksGrouped.get()) return
        if (groupIds.size < 2) return

        // Don't link a tracker onto a manga that has left the library.
        val members = groupIds.filter { getManga.await(it)?.favorite == true }
        if (members.size < 2) return

        val tracksByManga = members.associateWith { getTracks.await(it) }

        // One canonical track per tracker across the group; skip a tracker whose remote id disagrees
        // between members (different series slipped into the group) rather than guess which is right.
        val canonical = tracksByManga.values.flatten()
            .groupBy { it.trackerId }
            .filterValues { tracks -> tracks.mapTo(HashSet()) { it.remoteId }.size == 1 }
            .values.flatten()
            .let { canonicalTracksPerTracker(it, Track::trackerId, Track::lastChapterRead) }
        if (canonical.isEmpty()) return

        // A member that already has the canonical row is left alone; one that is missing it or behind it
        // is written (the manga_sync unique index replaces in place), so nobody keeps a stale copy.
        val toInsert = members.flatMap { memberId ->
            val own = tracksByManga.getValue(memberId).associateBy { it.trackerId }
            canonical.filter { it.lastChapterRead > (own[it.trackerId]?.lastChapterRead ?: -1.0) }
                .map { it.copy(mangaId = memberId) }
        }
        if (toInsert.isNotEmpty()) insertTrack.awaitAll(toInsert)
    }
}
