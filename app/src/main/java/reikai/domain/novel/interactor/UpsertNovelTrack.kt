package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.NovelTrack
import tachiyomi.core.common.util.system.logcat

@Inject
class UpsertNovelTrack(
    private val repository: NovelTrackRepository,
) {

    suspend fun await(track: NovelTrack) {
        try {
            repository.upsert(track)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    suspend fun awaitAll(tracks: List<NovelTrack>) {
        try {
            repository.upsertAll(tracks)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
