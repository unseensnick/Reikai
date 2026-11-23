package reikai.domain.novel.track

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import reikai.domain.entry.EntryId
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.UpsertNovelTrack
import reikai.domain.novel.model.NovelTrack
import reikai.domain.track.ChapterPushLock
import reikai.domain.track.ChapterPushOutcome
import reikai.domain.track.pushChapterProgress

/**
 * Novel twin of [eu.kanade.domain.track.interactor.TrackChapter], pinned by the [pushChapterProgress]
 * kernel both call: pushes a freshly read chapter number to every bound, logged-in tracker that is
 * behind, persisting to `novel_tracks`. On failure the update is queued in [NovelDelayedTrackingStore]
 * for [NovelDelayedTrackingUpdateWorker] to retry when online. Merge-aware via [GetNovelTracks.awaitGroup]:
 * a track bound on one source of a merged novel still advances when a sibling source's chapter is read,
 * since the reader keys on the chapter's own novel id, which differs across the group.
 */
@Inject
class TrackNovelChapter(
    private val getNovelTracks: GetNovelTracks,
    private val trackerManager: TrackerManager,
    private val upsertNovelTrack: UpsertNovelTrack,
    private val delayedTrackingStore: NovelDelayedTrackingStore,
) {

    suspend fun await(
        context: Context,
        novelId: Long,
        chapterNumber: Double,
        setupJobOnFailure: Boolean = true,
    ): ChapterPushOutcome = ChapterPushLock.around(getNovelTracks.groupIds(novelId).map(EntryId::Novel)) {
        push(context, getNovelTracks.awaitGroup(novelId), chapterNumber, setupJobOnFailure)
    }

    /** The delayed-tracking drain's push, of the queued row alone, as `TrackChapter.retry` does. */
    suspend fun retry(context: Context, track: NovelTrack, chapterNumber: Double): ChapterPushOutcome =
        ChapterPushLock.around(getNovelTracks.groupIds(track.novelId).map(EntryId::Novel)) {
            push(context, listOf(track), chapterNumber, setupJobOnFailure = false)
        }

    private suspend fun push(
        context: Context,
        tracks: List<NovelTrack>,
        chapterNumber: Double,
        setupJobOnFailure: Boolean,
    ): ChapterPushOutcome {
        return coroutineScope {
            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || chapterNumber <= track.lastChapterRead) {
                    return@mapNotNull null
                }

                async {
                    service to runCatching {
                        try {
                            val refreshed = service.refresh(track.toDbTrack()).toNovelTrack(idRequired = true)!!
                            val pushed = service.pushChapterProgress(refreshed.toDbTrack(), chapterNumber)
                            upsertNovelTrack.await(pushed.toNovelTrack(idRequired = true)!!)
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                NovelDelayedTrackingUpdateWorker.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .let(ChapterPushOutcome::of)
        }
    }
}
