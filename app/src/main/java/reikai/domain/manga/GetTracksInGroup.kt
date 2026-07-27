package reikai.domain.manga

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.track.GroupTrackReader
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track

/**
 * Manga binding of [GroupTrackReader]: the tracks bound on any member of a merge group, one per tracker,
 * where Mihon's [GetTracks] reads a single manga. The novel twin is
 * [reikai.domain.novel.interactor.GetNovelTracks.awaitGroup]; both run the same shared reader.
 */
class GetTracksInGroup(
    preferences: ReikaiLibraryPreferences,
    getTracks: GetTracks,
    mergeManager: MangaMergeManager,
) {

    private val reader = GroupTrackReader(
        sharingEnabled = { preferences.syncTrackerLinksGrouped.get() },
        relatedIds = { mergeManager.relatedIdsList(it) },
        readOne = { getTracks.await(it) },
        observeOne = { getTracks.subscribe(it) },
        trackerId = Track::trackerId,
        lastChapterRead = Track::lastChapterRead,
    )

    suspend fun await(mangaId: Long): List<Track> = reader.await(mangaId)

    fun subscribe(mangaId: Long): Flow<List<Track>> = reader.subscribe(mangaId)
}
