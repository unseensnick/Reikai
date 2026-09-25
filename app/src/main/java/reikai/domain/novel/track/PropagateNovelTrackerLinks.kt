package reikai.domain.novel.track

import dev.zacsweers.metro.Inject
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.novel.model.NovelTrack
import reikai.domain.track.handOutGroupTrackers

/** The novel side of [handOutGroupTrackers], gated by [ReikaiLibraryPreferences.syncTrackerLinksGrouped]. */
@Inject
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
    suspend fun distribute(groupIds: List<Long>) = handOutGroupTrackers(
        enabled = preferences.syncTrackerLinksGrouped.get(),
        groupIds = groupIds,
        isFavorite = { novelRepository.getById(it)?.favorite == true },
        tracksOf = { getNovelTracks.await(it) },
        trackerId = NovelTrack::trackerId,
        remoteId = NovelTrack::remoteId,
        lastChapterRead = NovelTrack::lastChapterRead,
        copyTo = { track, novelId -> track.copy(novelId = novelId) },
        writeAll = { insertNovelTrack.awaitAll(it) },
    )
}
