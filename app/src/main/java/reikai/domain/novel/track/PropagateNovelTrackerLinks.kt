package reikai.domain.novel.track

import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.novel.model.NovelTrack
import reikai.domain.track.canonicalTracksPerTracker

/**
 * Novel twin of [reikai.domain.manga.PropagateTrackerLinks]. Group-aware reads already share one track row
 * across a merged group, so we keep a single row while merged and only copy it onto each member when the
 * group is split, so every source keeps the tracker after an unmerge. Gated by
 * [ReikaiLibraryPreferences.syncTrackerLinksGrouped]; a tracker whose remote id disagrees across the group
 * is skipped. A member that is missing the group's furthest-read row, or behind it, is written; one that
 * already carries it is left alone.
 */
class PropagateNovelTrackerLinks(
    private val preferences: ReikaiLibraryPreferences,
    private val mergeManager: NovelMergeManager,
    private val novelRepository: NovelRepository,
    private val getNovelTracks: GetNovelTracks,
    private val insertNovelTrack: InsertNovelTrack,
) {

    /** Resolve [seedNovelId]'s group and copy each shared tracker onto every favorited member. */
    suspend fun fromSeed(seedNovelId: Long) = distribute(mergeManager.relatedIdsList(seedNovelId))

    /** Ensure every favorited member of [groupIds] carries each tracker bound anywhere in the group. */
    suspend fun distribute(groupIds: List<Long>) {
        if (!preferences.syncTrackerLinksGrouped.get()) return
        if (groupIds.size < 2) return

        val members = groupIds.filter { novelRepository.getById(it)?.favorite == true }
        if (members.size < 2) return

        val tracksByNovel = members.associateWith { getNovelTracks.await(it) }

        // One canonical track per tracker across the group; skip a tracker whose remote id disagrees
        // between members (different series slipped into the group) rather than guess which is right.
        val canonical = tracksByNovel.values.flatten()
            .groupBy { it.trackerId }
            .filterValues { tracks -> tracks.mapTo(HashSet()) { it.remoteId }.size == 1 }
            .values.flatten()
            .let { canonicalTracksPerTracker(it, NovelTrack::trackerId, NovelTrack::lastChapterRead) }
        if (canonical.isEmpty()) return

        // A member that already has the canonical row is left alone; one that is missing it or behind it
        // is written (the novel_tracks unique index replaces in place), so nobody keeps a stale copy.
        members.forEach { memberId ->
            val own = tracksByNovel.getValue(memberId).associateBy { it.trackerId }
            canonical.filter { it.lastChapterRead > (own[it.trackerId]?.lastChapterRead ?: -1.0) }
                .forEach { insertNovelTrack.await(it.copy(novelId = memberId)) }
        }
    }
}
