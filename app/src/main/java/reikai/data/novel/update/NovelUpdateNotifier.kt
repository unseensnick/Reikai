package reikai.data.novel.update

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat.NotificationWithIdAndTag
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import reikai.data.notification.hiddenEntryIds
import reikai.data.notification.mainActivityPendingIntent
import reikai.data.notification.newChaptersEntry
import reikai.data.notification.newChaptersSummary
import reikai.data.notification.notificationCover
import reikai.data.notification.offersDownloadAction
import reikai.data.notification.postedEntries
import reikai.data.notification.setNewChaptersEntry
import reikai.data.notification.setNewChaptersSummary
import reikai.data.notification.updateProgressPercent
import reikai.data.updateerror.setUpdateErrorContent
import reikai.data.updateerror.updateErrorPendingIntent
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.manga.AdultContentChecker
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.model.withCustomInfo
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Notifications for the background novel-update job: an ongoing progress entry (with a Cancel action)
 * while favorited novels are checked, then one entry per novel that gained chapters under a summary
 * that opens Updates, as the manga updater's does. Sibling of [reikai.novel.download.NovelDownloadNotifier].
 */
class NovelUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
    private val adultChecker: AdultContentChecker,
    private val getEntryCustomInfo: GetEntryCustomInfo,
) {

    /** Named and drawn by Edit info, as the manga updater's are; [hiddenNovelIds] reads the source values. */
    private suspend fun List<Novel>.shown(): List<Novel> =
        getEntryCustomInfo.overlay(this, { EntryId.Novel(it.id) }) { withCustomInfo(it) }

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

    /** Build the progress notification (also used for the worker's `getForegroundInfo`, with no [name]). */
    fun progress(name: String?, current: Int, total: Int): Notification =
        progressBuilder
            .setContentTitle(
                if (total == 0) {
                    context.stringResource(MR.strings.novel_library_update)
                } else {
                    context.stringResource(
                        MR.strings.notification_updating_progress,
                        updateProgressPercent(current, total),
                    )
                },
            )
            .setContentText(name)
            .setProgress(total, current, total == 0)
            .build()

    suspend fun showProgress(novel: Novel, current: Int, total: Int) {
        val name = listOf(novel).shown().single().title.takeUnless { novel.id in hiddenNovelIds(listOf(novel)) }
        context.notify(Notifications.ID_NOVEL_LIBRARY_PROGRESS, progress(name, current, total))
    }

    fun dismissProgress() {
        context.cancelNotification(Notifications.ID_NOVEL_LIBRARY_PROGRESS)
    }

    fun showUpdateErrors(failed: Int, log: Uri, tracked: Boolean) {
        if (failed == 0) return
        context.notify(Notifications.ID_NOVEL_LIBRARY_ERROR, Notifications.CHANNEL_NOVEL_LIBRARY_ERROR) {
            setUpdateErrorContent(context, failed, updateErrorPendingIntent(context, ContentType.NOVELS, log, tracked))
        }
    }

    /** One notification per updated novel (tap to read its first new chapter), grouped under a summary;
     *  skipped when nothing changed. The summary, cover and Download threshold are the manga updater's. */
    suspend fun showResults(sourceUpdates: List<Pair<Novel, List<NovelChapter>>>) {
        if (sourceUpdates.isEmpty()) return
        val hideAll = securityPreferences.hideNotificationContent.get()
        val hidden = hiddenNovelIds(sourceUpdates.map { it.first })
        val updates = sourceUpdates.map { it.first }.shown().zip(sourceUpdates.map { it.second })
        val perNovel = postedEntries(updates, hideAll).map { (novel, newChapters) ->
            val chapterIds = newChapters.map { it.id }.toLongArray()
            val isHidden = novel.id in hidden
            val cover = if (isHidden) null else context.notificationCover(novel.asNovelCover())
            val notification = context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_RESULT) {
                setNewChaptersEntry(
                    context,
                    newChaptersEntry(
                        novel.title.takeUnless { isHidden },
                        newChapters.map { it.chapterNumber },
                        newChapters.size,
                    ),
                )
                setSmallIcon(R.drawable.ic_reikai)
                cover?.let(::setLargeIcon)
                setGroup(Notifications.GROUP_NOVEL_NEW_CHAPTERS)
                setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                setAutoCancel(true)
                setContentIntent(
                    NotificationReceiver.openNovelChapterPendingActivity(context, novel, newChapters.first()),
                )
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
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_view_chapters),
                    NotificationReceiver.openNovelPendingActivity(context, novel),
                )
                if (offersDownloadAction(newChapters.size)) {
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
                }
            }.build()
            NotificationWithIdAndTag(Notifications.TAG_NOVEL_NEW_CHAPTERS, novel.id.hashCode(), notification)
        }
        val summary = context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_RESULT) {
            setContentTitle(context.stringResource(MR.strings.notification_new_chapters))
            // In the header line, as on the progress entry: the manga summary can sit beside this one.
            setSubText(context.stringResource(MR.strings.novel_library_update))
            setNewChaptersSummary(
                context,
                newChaptersSummary(
                    updates.map { (novel, _) ->
                        novel.title.takeUnless { novel.id in hidden }
                    },
                    hideAll,
                ),
            )
            setSmallIcon(R.drawable.ic_reikai)
            setGroup(Notifications.GROUP_NOVEL_NEW_CHAPTERS)
            setGroupSummary(true)
            setAutoCancel(true)
            setContentIntent(mainActivityPendingIntent(context, Constants.SHORTCUT_UPDATES))
        }.build()
        // The summary goes first, as the manga updater's does. Posted last it is the one Android
        // refuses at the package budget, and children with no summary of their own get an invented
        // one drawn with the launcher icon.
        context.notify(Notifications.ID_NOVEL_LIBRARY_RESULT, summary)
        context.notify(perNovel)
    }

    private suspend fun hiddenNovelIds(novels: List<Novel>): Set<Long> = hiddenEntryIds(
        novels,
        securityPreferences.hideNotificationContent.get(),
        securityPreferences.hideAdultNotificationContent.get(),
        Novel::id,
        adultChecker::adultNovelIdsAmong,
    )
}
