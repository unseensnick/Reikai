package reikai.domain.novel.interactor

import logcat.LogPriority
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelTrackRepository
import tachiyomi.core.common.util.system.logcat

class DeleteNovelTrack(
    private val repository: NovelTrackRepository,
    private val mergeManager: NovelMergeManager,
) {

    /** Remove the tracker from a single novel (the optimistic-bind rollback path). */
    suspend fun await(novelId: Long, trackerId: Long) {
        try {
            repository.delete(novelId, trackerId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Remove the tracker from every member of [novelId]'s merge group, so a tracker copied onto several
     * sources (a merged novel) is fully unbound rather than reappearing from a sibling's row.
     */
    suspend fun awaitGroup(novelId: Long, trackerId: Long) {
        mergeManager.relatedNovelIdsFor(novelId).forEach { await(it, trackerId) }
    }
}
