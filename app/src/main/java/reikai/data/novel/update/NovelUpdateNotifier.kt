package reikai.data.novel.update

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import reikai.domain.novel.model.Novel
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Notifications for the background novel-update job: an ongoing progress entry (with a Cancel action)
 * while favorited novels are checked, plus a one-shot result entry once any gained new chapters.
 * Sibling of [reikai.novel.download.NovelDownloadNotifier]. The result entry opens the library (there
 * is no dedicated novel-updates surface yet).
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

    /** One notification per updated novel (tap to open its details), grouped under a summary; skipped
     *  when nothing changed. Mirrors the manga per-title update notifications. */
    fun showResults(updates: List<Pair<Novel, Int>>) {
        if (updates.isEmpty()) return
        val perNovel = updates.map { (novel, newChapters) ->
            novel.id.hashCode() to context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_RESULT) {
                setContentTitle(novel.title)
                setContentText(
                    context.pluralStringResource(MR.plurals.notification_chapters_generic, newChapters, newChapters),
                )
                setSmallIcon(R.drawable.ic_book_24dp)
                setGroup(Notifications.GROUP_NOVEL_NEW_CHAPTERS)
                setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                setAutoCancel(true)
                setContentIntent(openNovelPendingIntent(novel))
            }.build()
        }
        val summary = context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_RESULT) {
            setContentTitle(context.stringResource(MR.strings.novel_new_chapters_available, updates.size))
            setSmallIcon(R.drawable.ic_book_24dp)
            setGroup(Notifications.GROUP_NOVEL_NEW_CHAPTERS)
            setGroupSummary(true)
            setAutoCancel(true)
            setContentIntent(openLibraryPendingIntent())
        }.build()
        with(context.notificationManager) {
            perNovel.forEach { (id, notification) -> notify(id, notification) }
            notify(Notifications.ID_NOVEL_LIBRARY_RESULT, summary)
        }
    }

    /** Deep-link a per-novel notification into its details via the [Constants.SHORTCUT_NOVEL] action. */
    private fun openNovelPendingIntent(novel: Novel): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Constants.SHORTCUT_NOVEL
            putExtra(Constants.NOVEL_SOURCE_EXTRA, novel.source)
            putExtra(Constants.NOVEL_URL_EXTRA, novel.url)
        }
        return PendingIntent.getActivity(
            context,
            novel.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
