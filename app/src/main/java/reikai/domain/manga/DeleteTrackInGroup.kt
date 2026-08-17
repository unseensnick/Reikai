package reikai.domain.manga

import dev.zacsweers.metro.Inject
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.track.trackGroupIds
import tachiyomi.domain.track.interactor.DeleteTrack

/**
 * Manga twin of [reikai.domain.novel.interactor.DeleteNovelTrack.awaitGroup]: unbind a tracker from every
 * member of a merged group. Reads span the group, so clearing one source's row alone would leave the
 * tracker driving the library's tracker filter, sort and grouping through a sibling's copy. With sharing
 * turned off the reads are per-source again, so the removal is too.
 */
@Inject
class DeleteTrackInGroup(
    private val preferences: ReikaiLibraryPreferences,
    private val deleteTrack: DeleteTrack,
    private val mergeManager: MangaMergeManager,
) {

    suspend fun await(mangaId: Long, trackerId: Long) {
        trackGroupIds(mangaId, { preferences.syncTrackerLinksGrouped.get() }, mergeManager::relatedIdsList)
            .forEach { deleteTrack.await(it, trackerId) }
    }
}
