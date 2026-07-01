package reikai.data.novel.update

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import reikai.domain.novel.NovelPreferences
import reikai.novel.update.LnPluginUpdateChecker
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * RK: periodic background check for light-novel plugin updates, bypassing the in-app
 * [LnPluginUpdateChecker.runIfStale] cache on its own WorkManager schedule. Writes the Browse-tab
 * badge count and posts a notification listing the outdated plugins. Mirrors Mihon's
 * [eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier] for manga extensions, on the cadence
 * the Yōkai fork used (12h, 1h flex, network required).
 */
class LnPluginUpdateJob(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val updates = Injekt.get<LnPluginUpdateChecker>().check()
            val prefs = Injekt.get<NovelPreferences>()
            prefs.pluginUpdatesCount().set(updates.size)
            prefs.lastLnPluginCheck().set(System.currentTimeMillis())

            if (updates.isNotEmpty()) {
                val names = updates.joinToString(", ") { it.entry.name }
                applicationContext.notify(
                    Notifications.ID_LN_PLUGIN_UPDATES,
                    Notifications.CHANNEL_LN_PLUGIN_UPDATE,
                ) {
                    setContentTitle(
                        applicationContext.stringResource(MR.strings.ln_plugins_update_available, updates.size),
                    )
                    setContentText(names)
                    setStyle(NotificationCompat.BigTextStyle().bigText(names))
                    setSmallIcon(R.drawable.ic_extension_24dp)
                    setContentIntent(NotificationReceiver.openExtensionsPendingActivity(applicationContext))
                    setAutoCancel(true)
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Log before retrying so a permanent failure (e.g. a malformed registry) leaves a trail
            // instead of retrying invisibly on WorkManager backoff forever.
            logcat(LogPriority.ERROR, e) { "LN plugin update check failed" }
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LnPluginUpdate"

        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<LnPluginUpdateJob>(
                12, TimeUnit.HOURS,
                1, TimeUnit.HOURS,
            )
                .addTag(TAG)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
