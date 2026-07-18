package reikai.domain.novel.track

import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack

/**
 * Novel twin of [reikai.domain.manga.PropagateTrackerLinks]. Group-aware reads
 * already share one track row across a merged group, so we keep a single row while merged and only copy
 * it onto each member when the group is split, so every source keeps the tracker after an unmerge.
 * Copy-on-write (each member ends up with its own row), gated by
 * [ReikaiLibraryPreferences.syncTrackerLinksGrouped]. Only missing trackers are added; an existing
 * binding is never overwritten; a tracker whose remote id disagrees across the group is skipped.
 */
class PropagateNovelTrackerLinks(
    private val preferences: ReikaiLibraryPreferences,
    private val mergeManager: NovelMergeManager,
    private val novelRepository: NovelRepository,
    private val getNovelTracks: GetNovelTracks,
    private val insertNovelTrack: InsertNovelTrack,
) {

    /** Resolve [seedNovelId]'s group and copy each shared tracker onto every favorited member. */
    suspend fun fromSeed(seedNovelId: Long) = distribute(mergeManager.relatedNovelIdsFor(seedNovelId))

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
            .mapNotNull { (trackerId, tracks) ->
                if (tracks.mapTo(HashSet()) { it.remoteId }.size == 1) trackerId to tracks.first() else null
            }
            .toMap()
        if (canonical.isEmpty()) return

        members.forEach { memberId ->
            val have = tracksByNovel.getValue(memberId).mapTo(HashSet()) { it.trackerId }
            canonical.filterKeys { it !in have }.values
                .forEach { insertNovelTrack.await(it.copy(novelId = memberId)) }
        }
    }
}
