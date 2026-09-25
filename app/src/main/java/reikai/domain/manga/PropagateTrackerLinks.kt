package reikai.domain.manga

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.track.handOutGroupTrackers
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

/**
 * The manga side of [handOutGroupTrackers], run just before a split rather than at merge time: while
 * merged, [GetTracksInGroup] already reads the whole group, so an earlier copy would only go stale.
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
    suspend fun distribute(groupIds: List<Long>) = handOutGroupTrackers(
        enabled = preferences.syncTrackerLinksGrouped.get(),
        groupIds = groupIds,
        isFavorite = { getManga.await(it)?.favorite == true },
        tracksOf = { getTracks.await(it) },
        trackerId = Track::trackerId,
        remoteId = Track::remoteId,
        lastChapterRead = Track::lastChapterRead,
        copyTo = { track, mangaId -> track.copy(mangaId = mangaId) },
        writeAll = { insertTrack.awaitAll(it) },
    )
}
