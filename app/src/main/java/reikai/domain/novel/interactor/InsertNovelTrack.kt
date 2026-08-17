package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.NovelTrack
import tachiyomi.core.common.util.system.logcat

@Inject
class InsertNovelTrack(
    private val repository: NovelTrackRepository,
) {

    suspend fun await(track: NovelTrack) {
        try {
            repository.insert(track)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    suspend fun awaitAll(tracks: List<NovelTrack>) {
        try {
            repository.insertAll(tracks)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
