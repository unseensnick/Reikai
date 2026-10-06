package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import reikai.domain.download.downloadNetworkIssue
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.R
import kotlin.time.Duration.Companion.seconds

/**
 * This worker owns the lifecycle of the downloader: it starts the downloader and stops it when the
 * worker is stopped by the system or there's no suitable network available.
 */
class DownloadWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var downloader: Downloader

    @Inject private lateinit var downloadPreferences: DownloadPreferences

    @Inject private lateinit var notifier: DownloadNotifier // RK

    // RK: why the worker paused the downloader, or null while it may fetch
    private var networkIssue: String? = null

    init {
        graph.inject(this)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // RK: the service can post this after the downloader's paused notice, so a worker starting paused shows that
        val notification = networkIssue?.let(notifier::pausedNotification)
            ?: applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
                setContentTitle(applicationContext.getString(R.string.download_notifier_downloader_title))
                setSmallIcon(android.R.drawable.stat_sys_download)
            }.build()
        return ForegroundInfo(
            Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        // RK: a resume after a force-kill would otherwise read the queue before its restore lands
        downloader.awaitQueueRestored()
        networkIssue = networkIssue() // RK: read by getForegroundInfo
        // RK --> a queue started without a suitable network waits for one below, as a dropped one does
        if (networkIssue != null && downloader.queueState.value.isEmpty()) return Result.failure()

        if (networkIssue == null && !downloader.start()) return Result.failure()
        // RK <--

        try {
            setForegroundSafely()

            // RK --> a network issue pauses the downloader and the worker waits it out, starting it again
            // once the issue clears. A user pause or an emptied queue ends the wait, as stop() clears isPaused.
            // A changed issue pauses again, so the notice never says Wi-Fi once the device is offline
            networkIssue?.let(downloader::stop)
            while (downloader.isRunning || downloader.isPaused) {
                delay(1.seconds)
                val issue = networkIssue()
                if (issue != null && (downloader.isRunning || issue != networkIssue)) downloader.stop(issue)
                if (issue == null && networkIssue != null && downloader.isPaused) downloader.start()
                networkIssue = issue
            }
            // RK <--
        } finally {
            if (downloader.isRunning && (networkIssue != null || isStopped)) downloader.stop(networkIssue)
        }

        return Result.success()
    }

    // RK: the rule is the kernel the novel drain pauses on too
    private fun networkIssue(): String? {
        val state = applicationContext.activeNetworkState()
        return downloadNetworkIssue(state, downloadPreferences.downloadOnlyOverWifi.get())
            ?.let { applicationContext.stringResource(it) }
    }

    companion object {
        private const val TAG = "Downloader"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }
    }
}
