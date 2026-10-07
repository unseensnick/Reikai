package reikai.novel.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import reikai.util.workRunningFlow

/**
 * Foreground worker that drains the novel download queue. Keeps the process alive (and shows a
 * progress notification) while [NovelDownloadManager.runQueue] downloads chapter text, so downloads
 * survive backgrounding; WorkManager re-runs it after a restart, where [NovelDownloadManager] restores
 * the persisted queue and resumes. Sibling of the manga
 * [eu.kanade.tachiyomi.data.download.DownloadWorker]; [NovelDownloadManager] applies the offline and
 * Wi-Fi-only pauses.
 */
class NovelDownloadWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    init {
        graph.inject(this)
    }

    @Inject private lateinit var manager: NovelDownloadManager

    @Inject private lateinit var securityPreferences: SecurityPreferences
    private val notifier = NovelDownloadNotifier(context, securityPreferences)

    // The waiting notice this worker last handed its service, which posts it again whenever another worker
    // goes foreground, so a newer reason must reach the service too or that post brings the old one back.
    private var requestedPause: NovelDownloadProgress.Paused? = null

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // The service may post this after the drain's own notice, so one starting off the network says why here too.
        requestedPause = manager.networkPause()
        val notification = notifier.progress(
            requestedPause ?: NovelDownloadProgress.Downloading(0, manager.queueState.value.size, "", isAdult = false),
        )
        val id = Notifications.ID_NOVEL_DOWNLOADER
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()
        return try {
            manager.runQueue(
                onProgress = { progress ->
                    notifier.show(progress)
                    if (progress is NovelDownloadProgress.Paused && progress != requestedPause) setForegroundSafely()
                },
                onError = notifier::onError,
            )
            Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } finally {
            // A user pause cancels this worker; leave a way back, as the manga downloader does.
            if (manager.isPausedByUser && manager.queueState.value.isNotEmpty()) {
                notifier.onPaused()
            } else {
                notifier.dismiss()
            }
        }
    }

    companion object {
        private const val TAG = "NovelDownloader"

        /** Start (or reuse) the downloader. KEEP so adding chapters to a running drain doesn't restart
         *  it; the running loop picks up the newly-queued items. */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<NovelDownloadWorker>()
                .addTag(TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        /** True while the drain worker runs, so the queue FAB can toggle Pause / Resume. [TAG] is also
         *  the unique work name, so the tag query sees the one request [start] keeps. */
        fun isRunningFlow(context: Context): Flow<Boolean> = context.workRunningFlow(TAG)
    }
}
