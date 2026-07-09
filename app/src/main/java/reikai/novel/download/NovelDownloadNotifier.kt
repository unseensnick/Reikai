package reikai.novel.download

import android.app.Notification
import android.content.Context
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Foreground progress notification for the novel chapter downloader. Minimal sibling of the manga
 * downloader notifier: one ongoing progress entry with a cancel action, no per-novel deep links
 * (there's no novel-downloads detail surface to open into).
 */
class NovelDownloadNotifier(private val context: Context) {

    private val builder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_DOWNLOADER) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelNovelDownloadPendingBroadcast(context),
            )
        }
    }

    /** Build the progress notification (also used for the worker's `getForegroundInfo`). */
    fun progress(title: String, current: Int, total: Int): Notification =
        builder
            .setContentTitle("${context.stringResource(MR.strings.label_download_queue)} ($current/$total)")
            .setContentText(title)
            .setProgress(total, current, total == 0)
            .build()

    fun show(title: String, current: Int, total: Int) {
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOADER, progress(title, current, total))
    }

    fun dismiss() {
        context.notificationManager.cancel(Notifications.ID_NOVEL_DOWNLOADER)
    }

    /**
     * Post a persistent failure notification when a chapter download gives up after all retries.
     * Without this a failed novel download was completely silent (only an ERROR row in the queue,
     * gone on restart). Mirrors the manga downloader's error notification; tapping opens the queue.
     */
    fun onError(novelTitle: String?, error: String?) {
        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_ERROR) {
            setContentTitle(
                novelTitle?.takeIf { it.isNotBlank() }
                    ?: context.stringResource(MR.strings.download_notifier_downloader_title),
            )
            setContentText(error ?: context.stringResource(MR.strings.download_notifier_unknown_error))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            setAutoCancel(true)
        }.build()
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOADER_ERROR, notification)
    }
}
