package reikai.data.novel.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.app.di.appGraph
import reikai.novel.update.LnPluginUpdateChecker
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Periodic background check for light-novel plugin updates, bypassing the in-app
 * [LnPluginUpdateChecker.runIfStale] cache on its own WorkManager schedule. Writes the Browse-tab
 * badge count and posts the update notice through [reikai.novel.update.LnPluginUpdateNotifier], on
 * the cadence the Yōkai fork used (12h, 1h flex, network required).
 */
class LnPluginUpdateJob(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val graph = applicationContext.appGraph
            val updates = graph.lnPluginUpdateChecker.check()
            graph.lnPluginUpdateNotifier.setPendingCount(updates.size)
            graph.novelPreferences.lastLnPluginCheck().set(System.currentTimeMillis())
            if (updates.isNotEmpty()) graph.lnPluginUpdateNotifier.promptUpdates(updates.map { it.entry.name })
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only an unexpected failure lands here: check() already logs and skips a registry that
            // cannot be fetched or parsed. Logged so a retry on WorkManager backoff is not invisible.
            logcat(LogPriority.ERROR, e) { "LN plugin update check failed" }
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LnPluginUpdate"

        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<LnPluginUpdateJob>(
                12,
                TimeUnit.HOURS,
                1,
                TimeUnit.HOURS,
            )
                .addTag(TAG)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
