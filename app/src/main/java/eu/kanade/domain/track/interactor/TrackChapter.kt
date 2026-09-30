package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.track.ChapterPushOutcome
import reikai.domain.track.pushChapterProgress
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.track.interactor.UpsertTrack

@Inject
class TrackChapter(
    // RK --> the reader keys on the chapter's own manga, which differs across a merged group, so the
    // group's tracks are read as one; the "is this tracker behind?" guard below would otherwise see a
    // sibling's stale row and push the remote service backwards.
    private val getTracks: GetTracksInGroup,
    // RK <--
    private val trackerManager: TrackerManager,
    private val upsertTrack: UpsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {

    // RK: returns the trackers the push failed at, so the mark-read toast can name them
    suspend fun await(
        context: Context,
        mangaId: Long,
        chapterNumber: Double,
        setupJobOnFailure: Boolean = true,
    ): ChapterPushOutcome {
        return withNonCancellableContext {
            val tracks = getTracks.await(mangaId)

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || chapterNumber <= track.lastChapterRead) {
                    return@mapNotNull null
                }

                // RK: each result keyed by its tracker, for ChapterPushOutcome
                async {
                    service to runCatching {
                        try {
                            // RK --> pushed through the shared pushChapterProgress kernel, which keeps the
                            // status and start date the tracker's update wrote, where upstream saved the row it
                            // sent, and starts the series on a first push of any chapter, where upstream needs 1
                            val refreshed = service.refresh(track.toDbTrack()).toDomainTrack(idRequired = true)!!
                            val pushed = service.pushChapterProgress(
                                refreshed.copy(lastChapterRead = chapterNumber).toDbTrack(),
                                progressBefore = refreshed.lastChapterRead,
                            )
                            upsertTrack.await(pushed.toDomainTrack(idRequired = true)!!)
                            // RK <--
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .let(ChapterPushOutcome::of) // RK: logs the failures as upstream's forEach did
        }
    }
}
