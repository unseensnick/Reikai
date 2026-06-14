package reikai.data.novel.update

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Notifications for the background novel-update job: an ongoing progress entry (with a Cancel action)
 * while favorited novels are checked, plus a one-shot result entry once any gained new chapters.
 * Sibling of [reikai.novel.download.NovelDownloadNotifier]. The result entry opens the library (there
 * is no dedicated novel-updates surface yet, that is P9).
 */
class NovelUpdateNotifier(private val context: Context) {

    private val progressBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.novel_library_update))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelNovelLibraryUpdatePendingBroadcast(context),
            )
        }
    }

    /** Build the progress notification (also used for the worker's `getForegroundInfo`). */
    fun progress(title: String, current: Int, total: Int): Notification =
        progressBuilder
            .setContentText(title)
            .setProgress(total, current, total == 0)
            .build()

    fun showProgress(title: String, current: Int, total: Int) {
        context.notificationManager.notify(Notifications.ID_NOVEL_LIBRARY_PROGRESS, progress(title, current, total))
    }

    fun dismissProgress() {
        context.notificationManager.cancel(Notifications.ID_NOVEL_LIBRARY_PROGRESS)
    }

    /** One-shot "N novels have new chapters" entry; skipped when nothing changed. */
    fun showResult(updatedCount: Int) {
        if (updatedCount <= 0) return
        val notification = context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_RESULT) {
            setContentTitle(context.stringResource(MR.strings.novel_new_chapters_available, updatedCount))
            setSmallIcon(R.drawable.ic_book_24dp)
            setAutoCancel(true)
            setContentIntent(openLibraryPendingIntent())
        }.build()
        context.notificationManager.notify(Notifications.ID_NOVEL_LIBRARY_RESULT, notification)
    }

    private fun openLibraryPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Constants.SHORTCUT_LIBRARY
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
