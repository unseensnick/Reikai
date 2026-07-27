package reikai.domain.novel.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import logcat.LogPriority
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.NovelTrack
import reikai.domain.track.GroupTrackReader
import tachiyomi.core.common.util.system.logcat

class GetNovelTracks(
    private val repository: NovelTrackRepository,
    private val mergeManager: NovelMergeManager,
    preferences: ReikaiLibraryPreferences,
) {

    // The group read is shared with the manga side (reikai.domain.manga.GetTracksInGroup); only the
    // novel repository reads below it are novel-specific.
    private val groupReader = GroupTrackReader(
        sharingEnabled = { preferences.syncTrackerLinksGrouped.get() },
        relatedIds = { mergeManager.relatedIdsList(it) },
        readOne = { await(it) },
        observeOne = { subscribe(it) },
        trackerId = NovelTrack::trackerId,
        lastChapterRead = NovelTrack::lastChapterRead,
    )

    suspend fun awaitOne(id: Long): NovelTrack? {
        return try {
            repository.getTrackById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    suspend fun await(novelId: Long): List<NovelTrack> {
        return try {
            repository.getTracksByNovelId(novelId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    fun subscribe(novelId: Long): Flow<List<NovelTrack>> = repository.getTracksByNovelIdAsFlow(novelId)

    /** Every novel track in the library, grouped by novel id. Mirrors [GetTracksPerManga.subscribe]. */
    fun subscribeAll(): Flow<Map<Long, List<NovelTrack>>> =
        repository.getTracksAsFlow().map { tracks -> tracks.groupBy(NovelTrack::novelId) }

    /** Tracks bound on any member of [novelId]'s merge group, one per tracker. */
    suspend fun awaitGroup(novelId: Long): List<NovelTrack> = groupReader.await(novelId)

    /** Reactive [awaitGroup]. */
    fun subscribeGroup(novelId: Long): Flow<List<NovelTrack>> = groupReader.subscribe(novelId)
}
