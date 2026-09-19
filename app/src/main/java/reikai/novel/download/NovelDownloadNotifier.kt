package reikai.novel.download

import android.app.Notification
import android.content.Context
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import reikai.data.notification.downloadErrorTitle
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Foreground progress notification for the novel chapter downloader, sibling of the manga downloader
 * notifier: an ongoing progress entry with pause, show entry and cancel, and a paused entry with
 * resume and cancel all. Tapping either opens the download queue.
 */
class NovelDownloadNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
) {

    private val builder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_DOWNLOADER) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
        }
    }

    /** What a user pause leaves behind, so the queue can be resumed or cleared from the shade. */
    fun onPaused() {
        val notification = context.notificationBuilder(Notifications.CHANNEL_NOVEL_DOWNLOADER) {
            setContentTitle(context.stringResource(MR.strings.chapter_paused))
            setContentText(context.stringResource(MR.strings.download_notifier_download_paused))
            setSmallIcon(R.drawable.ic_pause_24dp)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            addAction(
                R.drawable.ic_play_arrow_24dp,
                context.stringResource(MR.strings.action_resume),
                NotificationReceiver.resumeNovelDownloadsPendingBroadcast(context),
            )
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel_all),
                NotificationReceiver.cancelNovelDownloadPendingBroadcast(context),
            )
        }.build()
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOADER_PAUSED, notification)
    }

    /** Build the progress notification (also used for the worker's `getForegroundInfo`). */
    fun progress(progress: NovelDownloadProgress): Notification =
        builder
            .clearActions()
            .addAction(
                R.drawable.ic_pause_24dp,
                context.stringResource(MR.strings.action_pause),
                NotificationReceiver.pauseNovelDownloadsPendingBroadcast(context),
            )
            .apply {
                val novel = (progress as? NovelDownloadProgress.Downloading)?.novel ?: return@apply
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openNovelPendingActivity(context, novel),
                )
            }
            .addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelNovelDownloadPendingBroadcast(context),
            )
            .setContentTitle(
                "${context.stringResource(MR.strings.label_download_queue)} (${progress.current}/${progress.total})",
            )
            .setContentText(
                progress.shownText(
                    securityPreferences.hideNotificationContent.get(),
                    securityPreferences.hideAdultNotificationContent.get(),
                ),
            )
            .setProgress(progress.total, progress.current, progress.total == 0)
            .build()

    fun show(progress: NovelDownloadProgress) {
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOADER, progress(progress))
    }

    fun dismiss() {
        context.notificationManager.cancel(Notifications.ID_NOVEL_DOWNLOADER)
    }

    /**
     * Post a persistent failure notification when a chapter download gives up after all retries.
     * Without this a failed novel download was completely silent (only an ERROR row in the queue,
     * gone on restart). Mirrors the manga downloader's error notification; tapping opens the queue.
     */
    fun onError(novelTitle: String?, chapterName: String?, error: String?, isAdult: Boolean) {
        val title = downloadErrorTitle(
            novelTitle,
            chapterName,
            securityPreferences.hideAdultNotificationContent.get(),
            isAdult,
        )
        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_ERROR) {
            setContentTitle(title ?: context.stringResource(MR.strings.download_notifier_downloader_title))
            setContentText(error ?: context.stringResource(MR.strings.download_notifier_unknown_error))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            setAutoCancel(true)
        }.build()
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOADER_ERROR, notification)
    }
}
