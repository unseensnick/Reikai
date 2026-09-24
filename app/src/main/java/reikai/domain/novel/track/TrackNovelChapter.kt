package reikai.domain.novel.track

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.track.pushChapterProgress
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat

/**
 * Novel twin of [eu.kanade.domain.track.interactor.TrackChapter], pinned by the [pushChapterProgress]
 * kernel both call: pushes a freshly read chapter number to every bound, logged-in tracker that is
 * behind, persisting to `novel_tracks`. On failure the update is queued in [NovelDelayedTrackingStore]
 * for [NovelDelayedTrackingUpdateJob] to retry when online. Merge-aware via [GetNovelTracks.awaitGroup]:
 * a track bound on one source of a merged novel still advances when a sibling source's chapter is read,
 * since the reader keys on the chapter's own novel id, which differs across the group.
 */
@Inject
class TrackNovelChapter(
    private val getNovelTracks: GetNovelTracks,
    private val trackerManager: TrackerManager,
    private val insertNovelTrack: InsertNovelTrack,
    private val delayedTrackingStore: NovelDelayedTrackingStore,
) {

    suspend fun await(context: Context, novelId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            val tracks = getNovelTracks.awaitGroup(novelId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || chapterNumber <= track.lastChapterRead) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val refreshed = service.refresh(track.toDbTrack())
                                .toNovelTrack(idRequired = true)!!
                                .copy(lastChapterRead = chapterNumber)
                            val pushed = service.pushChapterProgress(refreshed.toDbTrack())
                            insertNovelTrack.await(pushed.toNovelTrack(idRequired = true)!!)
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                NovelDelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }
}
