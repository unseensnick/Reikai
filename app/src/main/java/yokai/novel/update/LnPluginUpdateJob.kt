package yokai.novel.update

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.jobIsRunning
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.notification
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.novel.NovelPreferences
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Periodic background check for light-novel plugin updates. Counterpart to manga's
 * [eu.kanade.tachiyomi.extension.ExtensionUpdateJob]: 12-hour interval with a 1-hour flex window
 * on a CONNECTED network constraint. Simpler than the manga job because there's no LN-side
 * installer to chain to: the result is a notification + a count for the Browse-tab badge, and
 * the user reinstalls plugins manually from Browse → Extensions → Light novels.
 *
 * Bypasses [LnPluginUpdateChecker.runIfStale]'s 6h cache because the WorkManager schedule already
 * rate-limits this path to twice a day at most. The on-launch / on-resume path in `MainActivity`
 * uses `runIfStale` for the silent count-only refresh between job firings.
 */
class LnPluginUpdateJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val checker: LnPluginUpdateChecker by inject()
    private val prefs: NovelPreferences by inject()

    override suspend fun doWork(): Result = coroutineScope {
        val updates = try {
            checker.check()
        } catch (e: Exception) {
            return@coroutineScope Result.failure()
        }
        prefs.pluginUpdatesCount().set(updates.size)
        prefs.lastLnPluginCheck().set(System.currentTimeMillis())
        if (updates.isNotEmpty()) {
            postNotification(updates)
        }
        Result.success()
    }

    private fun postNotification(updates: List<LnPluginUpdate>) {
        NotificationManagerCompat.from(applicationContext).apply {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return@apply
            }
            notify(
                Notifications.ID_UPDATES_TO_LN_PLUGINS,
                applicationContext.notification(Notifications.CHANNEL_UPDATES_TO_LN_PLUGINS) {
                    val ctx = applicationContext.localeContext
                    setContentTitle(
                        ctx.getString(
                            MR.plurals.ln_plugin_updates_available,
                            updates.size,
                            updates.size,
                        ),
                    )
                    val names = updates.joinToString(", ") { it.entry.name }
                    setContentText(names)
                    setStyle(NotificationCompat.BigTextStyle().bigText(names))
                    setSmallIcon(R.drawable.ic_extension_update_24dp)
                    color = ContextCompat.getColor(ctx, R.color.secondaryTachiyomi)
                    setContentIntent(NotificationReceiver.openExtensionsPendingActivity(ctx))
                    setAutoCancel(true)
                },
            )
        }
    }

    companion object {
        private const val TAG = "LnPluginUpdate"

        fun setupTask(context: Context) {
            WorkManager.getInstance(context).jobIsRunning(TAG)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<LnPluginUpdateJob>(
                12, TimeUnit.HOURS,
                1, TimeUnit.HOURS,
            )
                .addTag(TAG)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
