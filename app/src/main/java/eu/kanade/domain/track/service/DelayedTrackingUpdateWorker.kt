package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import reikai.domain.track.drainDelayedTracking
import reikai.domain.track.enqueueDelayedTracking
import tachiyomi.domain.track.interactor.GetTracks

class DelayedTrackingUpdateWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var getTracks: GetTracks

    @Inject lateinit var trackChapter: TrackChapter

    @Inject lateinit var delayedTrackingStore: DelayedTrackingStore

    // RK: injected in init rather than at the top of doWork, since WorkManager may call getForegroundInfo first.
    init {
        graph.inject(this)
    }

    // RK --> the drain is the drainDelayedTracking kernel the novel job runs too; port upstream changes there
    override suspend fun doWork(): Result = drainDelayedTracking(
        runAttemptCount = runAttemptCount,
        items = delayedTrackingStore::getItems,
        remove = delayedTrackingStore::remove,
        trackOf = { getTracks.awaitOne(it) },
    ) { track, lastChapterRead ->
        trackChapter.await(context, track.mangaId, lastChapterRead, setupJobOnFailure = false)
    }
    // RK <--

    companion object {
        private const val TAG = "DelayedTrackingUpdate"

        // RK --> the request is the enqueueDelayedTracking kernel the novel job uses; port upstream changes there
        fun setupTask(context: Context) {
            enqueueDelayedTracking<DelayedTrackingUpdateWorker>(context, TAG)
        }
        // RK <--
    }
}
