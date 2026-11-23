package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateWorker
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import reikai.domain.entry.EntryId
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.track.ChapterPushLock
import reikai.domain.track.ChapterPushOutcome
import reikai.domain.track.pushChapterProgress
import tachiyomi.domain.track.interactor.UpsertTrack
import tachiyomi.domain.track.model.Track

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
        // RK --> one push per merge group at a time, see ChapterPushLock
    ): ChapterPushOutcome = ChapterPushLock.around(getTracks.groupIds(mangaId).map(EntryId::Manga)) {
        push(context, mangaId, getTracks.await(mangaId), chapterNumber, setupJobOnFailure)
    }
    // RK <--

    // RK --> the delayed-tracking drain's push, of the queued row alone. The queue keeps the number read but
    // not which source of a merged series it was read on, so no other row can be judged against it: a
    // server tracker on that row's source would take a sibling source's numbering. Upstream retried every
    // tracker of the row's entry.
    suspend fun retry(context: Context, track: Track, chapterNumber: Double): ChapterPushOutcome =
        ChapterPushLock.around(getTracks.groupIds(track.mangaId).map(EntryId::Manga)) {
            push(context, track.mangaId, listOf(track), chapterNumber, setupJobOnFailure = false)
        }

    // [mangaId] is the entry the chapter was read on, [tracks] the rows to push to
    private suspend fun push(
        context: Context,
        mangaId: Long,
        tracks: List<Track>,
        chapterNumber: Double,
        setupJobOnFailure: Boolean,
    ): ChapterPushOutcome {
        return coroutineScope {
            // RK <--
            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || chapterNumber <= track.lastChapterRead) {
                    return@mapNotNull null
                }
                // RK: a server's chapter numbers belong to the source it serves, so a group row a server
                // tracker holds on a sibling source never takes the number read here
                if (service is EnhancedTracker && track.mangaId != mangaId) return@mapNotNull null

                // RK: each result keyed by its tracker, for ChapterPushOutcome
                async {
                    service to runCatching {
                        try {
                            // RK --> pushed through the shared pushChapterProgress kernel, which keeps the
                            // status and start date the tracker's update wrote, where upstream saved the row it
                            // sent, starts the series on a first push of any chapter, where upstream needs 1,
                            // and never moves the refreshed service back, where upstream sends any chapter
                            val refreshed = service.refresh(track.toDbTrack()).toDomainTrack(idRequired = true)!!
                            val pushed = service.pushChapterProgress(refreshed.toDbTrack(), chapterNumber)
                            upsertTrack.await(pushed.toDomainTrack(idRequired = true)!!)
                            // RK <--
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateWorker.setupTask(context)
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
