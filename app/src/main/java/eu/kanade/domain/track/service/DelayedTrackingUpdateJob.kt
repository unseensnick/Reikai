package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.util.system.workManager
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import reikai.domain.track.drainDelayedTracking
import tachiyomi.domain.track.interactor.GetTracks
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var getTracks: GetTracks

    @Inject lateinit var trackChapter: TrackChapter

    @Inject lateinit var delayedTrackingStore: DelayedTrackingStore

    // RK: injected in init rather than at the top of doWork, see metro-di-migration.md "Workers inject".
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

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
