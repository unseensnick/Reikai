package reikai.domain.novel.interactor

import logcat.LogPriority
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.NovelTrack
import tachiyomi.core.common.util.system.logcat

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
}
