package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.track.trackGroupIds
import tachiyomi.core.common.util.system.logcat

@Inject
class DeleteNovelTrack(
    private val repository: NovelTrackRepository,
    private val mergeManager: NovelMergeManager,
    private val preferences: ReikaiLibraryPreferences,
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
     * sources (a merged novel) is fully unbound rather than reappearing from a sibling's row. With sharing
     * turned off the reads are per-source again, so the removal is too.
     */
    suspend fun awaitGroup(novelId: Long, trackerId: Long) {
        trackGroupIds(novelId, { preferences.syncTrackerLinksGrouped.get() }, mergeManager::relatedIdsList)
            .forEach { await(it, trackerId) }
    }
}
