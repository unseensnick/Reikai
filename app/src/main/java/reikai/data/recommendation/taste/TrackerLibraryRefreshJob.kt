package reikai.data.recommendation.taste

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import mihon.app.di.appGraph
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import reikai.util.workRunningFlow
import java.util.concurrent.TimeUnit

/**
 * Background pull of the user's tracker libraries into the taste cache: periodic, on the schedule the
 * user picks (`trackerLibraryAutoRefreshHours`: 0 never / 168 weekly / 720 monthly), and one-off for
 * the manual Refresh now, so that pull outlives the settings screen. Independent of the in-app
 * `refreshIfStale` bootstrap, which keeps the cache fresh during normal use.
 */
class TrackerLibraryRefreshJob(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            applicationContext.appGraph.refreshTrackerLibrary.await()
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TrackerLibraryRefresh"

        // Its own unique name: under KEEP, sharing TAG would find the enqueued periodic work and do nothing.
        private const val MANUAL_WORK_NAME = "TrackerLibraryRefreshNow"

        /** (Re)schedule or cancel the periodic pull from the auto-refresh interval preference. */
        fun setupTask(context: Context, prefInterval: Int? = null) {
            val interval = prefInterval ?: context.appGraph.reikaiRecommendationPreferences
                .trackerLibraryAutoRefreshHours.get()
            if (interval > 0) {
                val request = PeriodicWorkRequestBuilder<TrackerLibraryRefreshJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                )
                    .addTag(TAG)
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .build()
                context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                context.workManager.cancelUniqueWork(TAG)
            }
        }

        /** Starts the manual pull, or returns false when one is running or the cooldown has not passed. */
        fun startNow(context: Context): Boolean {
            if (context.workManager.isRunning(MANUAL_WORK_NAME)) return false
            if (!context.appGraph.refreshTrackerLibrary.tryStartManual()) return false
            val request = OneTimeWorkRequestBuilder<TrackerLibraryRefreshJob>()
                .addTag(MANUAL_WORK_NAME)
                .build()
            context.workManager.enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return true
        }

        fun isRunningFlow(context: Context): Flow<Boolean> = context.workRunningFlow(MANUAL_WORK_NAME)
    }
}
