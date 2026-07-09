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
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Foreground worker that drains the novel download queue. Keeps the process alive (and shows a
 * progress notification) while [NovelDownloadManager.runQueue] downloads chapter text, so downloads
 * survive backgrounding; WorkManager re-runs it after a restart, where [NovelDownloadManager] restores
 * the persisted queue and resumes. Sibling of the manga
 * [eu.kanade.tachiyomi.data.download.DownloadJob], minus connectivity gating (text is tiny).
 */
class NovelDownloadJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val manager: NovelDownloadManager = Injekt.get()
    private val notifier = NovelDownloadNotifier(context)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progress("", 0, manager.queueState.value.size)
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
                onProgress = { current, total, title -> notifier.show(title, current, total) },
                onError = { title, error -> notifier.onError(title, error) },
            )
            Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } finally {
            notifier.dismiss()
        }
    }

    companion object {
        private const val TAG = "NovelDownloader"

        /** Start (or reuse) the downloader. KEEP so adding chapters to a running drain doesn't restart
         *  it; the running loop picks up the newly-queued items. */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<NovelDownloadJob>()
                .addTag(TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
