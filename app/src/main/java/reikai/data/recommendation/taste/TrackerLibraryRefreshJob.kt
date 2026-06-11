package reikai.data.recommendation.taste

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.util.system.workManager
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * RK: periodic background pull of the user's tracker libraries into the taste cache, on the schedule
 * the user picks (`trackerLibraryAutoRefreshHours`: 0 never / 168 weekly / 720 monthly). Independent
 * of the in-app `refreshIfStale` bootstrap, which keeps the cache fresh during normal use; this just
 * adds a guaranteed background refresh cadence. WorkManager persists the schedule across reboots.
 */
class TrackerLibraryRefreshJob(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Injekt.get<RefreshTrackerLibrary>().await()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TrackerLibraryRefresh"

        /** (Re)schedule or cancel the periodic pull from the auto-refresh interval preference. */
        fun setupTask(context: Context, prefInterval: Int? = null) {
            val interval = prefInterval ?: Injekt.get<ReikaiRecommendationPreferences>()
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
    }
}
