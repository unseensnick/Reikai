package reikai.data.novel.update

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import reikai.data.notification.NOTIF_TITLE_MAX_LEN
import reikai.data.notification.hiddenEntryIds
import reikai.data.notification.newChaptersDescription
import reikai.data.notification.shownEntryName
import reikai.data.updateerror.updateErrorPendingIntent
import reikai.domain.library.ContentType
import reikai.domain.novel.isLewd
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.math.RoundingMode
import java.text.NumberFormat

/**
 * Notifications for the background novel-update job: an ongoing progress entry (with a Cancel action)
 * while favorited novels are checked, plus a one-shot result entry once any gained new chapters.
 * Sibling of [reikai.novel.download.NovelDownloadNotifier]. The result entry opens the library (there
 * is no dedicated novel-updates surface yet).
 */
class NovelUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
) {

    /** The same formatter the manga updater's progress title uses, so both read alike. */
    private val percentFormatter = NumberFormat.getPercentInstance().apply {
        roundingMode = RoundingMode.DOWN
        maximumFractionDigits = 0
    }

    private val progressBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.novel_library_update))
            // In the header line, because the title now carries the percentage and both libraries can
            // be updating at once: two entries reading "Updating library… (13%)" say nothing about
            // which is which.
            setSubText(context.stringResource(MR.strings.novel_library_update))
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

    /** Build the progress notification (also used for the worker's `getForegroundInfo`, with no [novel]). */
    fun progress(novel: Novel?, current: Int, total: Int): Notification =
        progressBuilder
            .setContentTitle(
                if (total == 0) {
                    context.stringResource(MR.strings.novel_library_update)
                } else {
                    context.stringResource(
                        MR.strings.notification_updating_progress,
                        percentFormatter.format(current.toFloat() / total),
                    )
                },
            )
            .setContentText(
                novel?.let {
                    shownEntryName(
                        it.title,
                        securityPreferences.hideNotificationContent.get(),
                        securityPreferences.hideAdultNotificationContent.get(),
                        it.isLewd(),
                    )
                },
            )
            .setProgress(total, current, total == 0)
            .build()

    fun showProgress(novel: Novel, current: Int, total: Int) {
        context.notify(Notifications.ID_NOVEL_LIBRARY_PROGRESS, progress(novel, current, total))
    }

    fun dismissProgress() {
        context.cancelNotification(Notifications.ID_NOVEL_LIBRARY_PROGRESS)
    }

    /** The twin of the manga updater's error notification; novels used to only log a failure. */
    fun showUpdateErrors(failed: Int, log: Uri, tracked: Boolean) {
        if (failed == 0) return
        context.notify(Notifications.ID_NOVEL_LIBRARY_ERROR, Notifications.CHANNEL_NOVEL_LIBRARY_ERROR) {
            setContentTitle(context.pluralStringResource(MR.plurals.notification_update_error, failed, failed))
            setContentText(context.stringResource(MR.strings.action_show_errors))
            setSmallIcon(R.drawable.ic_reikai)
            setAutoCancel(true)
            setContentIntent(updateErrorPendingIntent(context, ContentType.NOVELS, log, tracked))
        }
    }

    /** One notification per updated novel (tap to open its details), grouped under a summary; skipped
     *  when nothing changed. Mirrors the manga per-title update notifications. */
    suspend fun showResults(updates: List<Pair<Novel, List<NovelChapter>>>) {
        if (updates.isEmpty()) return
        val hideAll = securityPreferences.hideNotificationContent.get()
        val hidden = hiddenNovelIds(
            updates.map { it.first },
            hideAll,
            securityPreferences.hideAdultNotificationContent.get(),
        )
        // Hidden, only the count is posted, as the manga updater posts no per-series entries then.
        val perNovel = if (hideAll) {
            emptyList()
        } else {
            updates.take(Notifications.MAX_ENTRY_UPDATE_NOTIFICATIONS).map { (novel, newChapters) ->
                val chapterIds = newChapters.map { it.id }.toLongArray()
                val isHidden = novel.id in hidden
                // Names the chapters through the shared rule rather than counting them, so a novel row
                // reads like a manga one: "Chapters 1, 2, 3 and 10 more". A hidden one only counts them.
                val description = if (isHidden) {
                    context.pluralStringResource(
                        MR.plurals.notification_chapters_generic,
                        newChapters.size,
                        newChapters.size,
                    )
                } else {
                    context.newChaptersDescription(newChapters.map { it.chapterNumber }, newChapters.size)
                }
                novel.id.hashCode() to context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_RESULT) {
                    // Chopped for the same reason the manga twin is: a collapsed group draws the title and
                    // the chapters on one line, and a long title pushed the chapters off the end.
                    setContentTitle(
                        if (isHidden) {
                            context.stringResource(MR.strings.notification_new_chapters)
                        } else {
                            novel.title.chop(NOTIF_TITLE_MAX_LEN)
                        },
                    )
                    setContentText(description)
                    setStyle(NotificationCompat.BigTextStyle().bigText(description))
                    setSmallIcon(R.drawable.ic_reikai)
                    setGroup(Notifications.GROUP_NOVEL_NEW_CHAPTERS)
                    setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                    setAutoCancel(true)
                    setContentIntent(openNovelPendingIntent(novel))
                    addAction(
                        R.drawable.ic_done_24dp,
                        context.stringResource(MR.strings.action_mark_as_read),
                        NotificationReceiver.markNovelAsReadPendingBroadcast(
                            context,
                            novel.id,
                            chapterIds,
                            Notifications.ID_NOVEL_LIBRARY_RESULT,
                        ),
                    )
                    addAction(
                        android.R.drawable.stat_sys_download_done,
                        context.stringResource(MR.strings.action_download),
                        NotificationReceiver.downloadNovelChaptersPendingBroadcast(
                            context,
                            novel.id,
                            chapterIds,
                            Notifications.ID_NOVEL_LIBRARY_RESULT,
                        ),
                    )
                }.build()
            }
        }
        val summary = context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_RESULT) {
            setContentTitle(context.stringResource(MR.strings.novel_new_chapters_available, updates.size))
            setSmallIcon(R.drawable.ic_reikai)
            setGroup(Notifications.GROUP_NOVEL_NEW_CHAPTERS)
            setGroupSummary(true)
            setAutoCancel(true)
            setContentIntent(openLibraryPendingIntent())
        }.build()
        // The summary goes first, as the manga updater's does. Posted last it is the one Android
        // refuses at the package budget, and children with no summary of their own get an invented
        // one drawn with the launcher icon.
        context.notify(Notifications.ID_NOVEL_LIBRARY_RESULT, summary)
        perNovel.forEach { (id, notification) -> context.notify(id, notification) }
    }

    /** Deep-link a per-novel notification into its details via the [Constants.SHORTCUT_NOVEL] action. */
    private fun openNovelPendingIntent(novel: Novel): PendingIntent =
        NotificationReceiver.openNovelPendingActivity(context, novel)

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

/** The novels among [novels] a notification must leave unnamed. A novel's only adult signal is its genres. */
internal suspend fun hiddenNovelIds(novels: List<Novel>, hideAll: Boolean, hideAdult: Boolean): Set<Long> =
    hiddenEntryIds(novels, hideAll, hideAdult, Novel::id) { among ->
        among.filter(Novel::isLewd).mapTo(mutableSetOf(), Novel::id)
    }
