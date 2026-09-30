package reikai.domain.novel.track

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.system.workManager
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.track.drainDelayedTracking
import java.util.concurrent.TimeUnit

/**
 * Drains the novel tracking queue through the same kernel as Mihon's manga job. A class of its own
 * because WorkManager keys unique work by tag, and the two queues retry independently.
 */
class NovelDelayedTrackingUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    @Inject private lateinit var getNovelTracks: GetNovelTracks

    @Inject private lateinit var trackNovelChapter: TrackNovelChapter

    @Inject private lateinit var delayedTrackingStore: NovelDelayedTrackingStore

    private val graph: AppGraph = context.metroGraph()

    init {
        graph.inject(this)
    }

    override suspend fun doWork(): Result = drainDelayedTracking(
        runAttemptCount = runAttemptCount,
        items = delayedTrackingStore::getItems,
        remove = delayedTrackingStore::remove,
        trackOf = { getNovelTracks.awaitOne(it) },
    ) { track, lastChapterRead ->
        trackNovelChapter.await(context, track.novelId, lastChapterRead, setupJobOnFailure = false)
    }

    companion object {
        private const val TAG = "NovelDelayedTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<NovelDelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
