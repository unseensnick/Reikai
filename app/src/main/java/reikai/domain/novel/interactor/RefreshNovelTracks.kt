package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import reikai.domain.novel.track.toDbTrack
import reikai.domain.novel.track.toNovelTrack

/**
 * Novel twin of [eu.kanade.domain.track.interactor.RefreshTracks]: pulls the latest remote state for
 * every bound novel track and persists it. No EnhancedTracker chapter-sync (a no-op for the four
 * light-novel trackers). Merge-group-aware: refreshes tracks bound on any member of the group.
 *
 * @return the failed updates.
 */
class RefreshNovelTracks(
    private val getNovelTracks: GetNovelTracks,
    private val trackerManager: TrackerManager,
    private val insertNovelTrack: InsertNovelTrack,
) {

    suspend fun await(novelId: Long): List<Pair<Tracker?, Throwable>> {
        return supervisorScope {
            getNovelTracks.awaitGroup(novelId)
                .map { it to trackerManager.get(it.trackerId) }
                .filter { (_, service) -> service?.isLoggedIn == true }
                .map { (track, service) ->
                    async {
                        return@async try {
                            val updatedTrack = service!!.refresh(track.toDbTrack()).toNovelTrack()!!
                            insertNovelTrack.await(updatedTrack)
                            null
                        } catch (e: Throwable) {
                            service to e
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }
}
