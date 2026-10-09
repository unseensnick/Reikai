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
import reikai.domain.novel.model.Novel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import mihon.icons.materialsymbols.R as MaterialSymbolsR

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
        val notification = paused(context.stringResource(MR.strings.download_notifier_download_paused))
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOADER_PAUSED, notification)
    }

    /**
     * Build the worker's notification (also its `getForegroundInfo`). A drain waiting for a network shows
     * the paused notice saying why, as the manga worker does, on the worker's own id.
     */
    fun progress(progress: NovelDownloadProgress): Notification = when (progress) {
        is NovelDownloadProgress.Paused -> paused(progress.reason)
        is NovelDownloadProgress.Downloading -> downloading(progress)
    }

    private fun paused(reason: String): Notification =
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_DOWNLOADER) {
            setContentTitle(context.stringResource(MR.strings.chapter_paused))
            setContentText(reason)
            setSmallIcon(MaterialSymbolsR.drawable.rounded_filled_pause)
            // A drain waiting for a network posts this again at every recheck.
            setOnlyAlertOnce(true)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            addAction(
                MaterialSymbolsR.drawable.rounded_filled_play_arrow,
                context.stringResource(MR.strings.action_resume),
                NotificationReceiver.resumeNovelDownloadsPendingBroadcast(context),
            )
            addAction(
                MaterialSymbolsR.drawable.rounded_close,
                context.stringResource(MR.strings.action_cancel_all),
                NotificationReceiver.cancelNovelDownloadPendingBroadcast(context),
            )
        }.build()

    private fun downloading(progress: NovelDownloadProgress.Downloading): Notification =
        builder
            .clearActions()
            .addAction(
                MaterialSymbolsR.drawable.rounded_filled_pause,
                context.stringResource(MR.strings.action_pause),
                NotificationReceiver.pauseNovelDownloadsPendingBroadcast(context),
            )
            .apply {
                val novel = progress.novel ?: return@apply
                addAction(
                    MaterialSymbolsR.drawable.rounded_book,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openNovelPendingActivity(context, novel),
                )
            }
            .addAction(
                MaterialSymbolsR.drawable.rounded_close,
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
     * A failed row loses its reason on restart, so this is the lasting record. Mirrors the manga
     * downloader's error notification, pinned by downloadErrorTitle (DownloadErrorTitleTest), which
     * both title it through; tapping opens the queue.
     */
    fun onError(novel: Novel?, chapterName: String?, error: String?, isAdult: Boolean) {
        val title = downloadErrorTitle(
            novel?.title,
            chapterName,
            securityPreferences.hideAdultNotificationContent.get(),
            isAdult,
        )
        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_ERROR) {
            setContentTitle(title ?: context.stringResource(MR.strings.download_notifier_downloader_title))
            setContentText(error ?: context.stringResource(MR.strings.download_notifier_unknown_error))
            setSmallIcon(MaterialSymbolsR.drawable.rounded_filled_warning)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            if (novel != null) {
                addAction(
                    MaterialSymbolsR.drawable.rounded_book,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openNovelPendingActivity(context, novel),
                )
            }
            setAutoCancel(true)
        }.build()
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOADER_ERROR, notification)
    }
}
