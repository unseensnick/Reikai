package eu.kanade.tachiyomi.data.library

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.notification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelChapter
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

/**
 * Novel-side parallel of [LibraryUpdateNotifier]. Wraps progress, error, skipped, queue-warning,
 * and result notifications for the novel update job, routed through the dedicated
 * `CHANNEL_NOVEL_LIBRARY_*` channels (so users can mute novel updates without losing manga ones).
 *
 * Two reductions from the manga notifier (both deferred until a later phase wires the supporting
 * UI):
 *
 * * The "result" (new-chapters) notification has no per-novel action buttons — manga's
 *   "Mark as read" / "View chapters" both call `NotificationReceiver` helpers that require the
 *   reader UI. The novel reader isn't built yet (Phase 7E+), so the buttons would have no
 *   destination. Tapping the body still opens MainActivity.
 * * The progress notification's "Cancel" action wires through
 *   `NotificationReceiver.cancelNovelLibraryUpdatePendingBroadcast`, which is currently a no-op
 *   stub; C14d replaces the receiver's body with `NovelUpdateJob.stop(context)`.
 */
class NovelUpdateNotifier(private val context: Context) : KoinComponent {

    private val preferences: NovelPreferences by inject()

    private val cancelIntent by lazy {
        NotificationReceiver.cancelNovelLibraryUpdatePendingBroadcast(context)
    }

    private val notificationBitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_PROGRESS) {
            setContentTitle(context.getString(MR.strings.updating_library))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setLargeIcon(notificationBitmap)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)
            color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
            addAction(R.drawable.ic_close_24dp, context.getString(AR.string.cancel), cancelIntent)
        }
    }

    /**
     * Shows the notification containing the currently updating novel and the progress.
     */
    fun showProgressNotification(novel: Novel, current: Int, total: Int) {
        context.notificationManager.notify(
            Notifications.ID_NOVEL_LIBRARY_PROGRESS,
            progressNotificationBuilder
                .setContentTitle("${context.getString(MR.strings.updating_library)} (${current + 1}/$total)")
                .setContentText(if (preferences.hideNotificationContent().get()) null else novel.title)
                .setProgress(total, current, false)
                .build(),
        )
    }

    /**
     * Shows notification containing update entries that failed with action to open full log.
     */
    fun showUpdateErrorNotification(errors: List<String>, uri: Uri) {
        if (errors.isEmpty()) return
        val pendingIntent = NotificationReceiver.openErrorOrSkippedLogPendingActivity(context, uri)
        context.notificationManager.notify(
            Notifications.ID_NOVEL_LIBRARY_ERROR,
            context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_ERROR) {
                setContentTitle(context.getString(MR.strings.notification_update_error, errors.size))
                setContentText(context.getString(MR.strings.tap_to_see_details))
                setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        errors.joinToString("\n") { it.chop(TITLE_MAX_LEN) },
                    ),
                )
                setContentIntent(pendingIntent)
                setSmallIcon(R.drawable.ic_yokai)
                addAction(
                    R.drawable.ic_file_open_24dp,
                    context.getString(MR.strings.open_log),
                    pendingIntent,
                )
            }.build(),
        )
    }

    /**
     * Shows notification containing update entries that were skipped with actions to open full log
     * and learn more.
     */
    fun showUpdateSkippedNotification(skips: List<String>, uri: Uri) {
        if (skips.isEmpty()) return

        context.notificationManager.notify(
            Notifications.ID_NOVEL_LIBRARY_SKIPPED,
            context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_SKIPPED) {
                setContentTitle(context.getString(MR.strings.notification_update_skipped, skips.size))
                setContentText(context.getString(MR.strings.tap_to_learn_more))
                setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        skips.joinToString("\n") { it.chop(TITLE_MAX_LEN) },
                    ),
                )
                setContentIntent(NotificationHandler.openUrl(context, HELP_SKIPPED_URL))
                setSmallIcon(R.drawable.ic_yokai)
                addAction(
                    R.drawable.ic_file_open_24dp,
                    context.getString(MR.strings.open_log),
                    NotificationReceiver.openErrorOrSkippedLogPendingActivity(context, uri),
                )
                addAction(
                    R.drawable.ic_help_outline_24dp,
                    context.getString(MR.strings.learn_why),
                    NotificationHandler.openUrl(context, HELP_SKIPPED_URL),
                )
            }.build(),
        )
    }

    /**
     * Shows the notification containing the result of the update done by the job. Keyed by
     * [LibraryNovel] (not [Novel]) to mirror [LibraryUpdateNotifier.showResultNotification]'s
     * signature, so the call site in [NovelUpdateJob] passes its `newUpdates` map directly.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun showResultNotification(newUpdates: Map<LibraryNovel, Array<NovelChapter>>) {
        val updates = newUpdates.toMap()
        GlobalScope.launch {
            val notifications = ArrayList<Pair<Notification, Int>>()
            if (!preferences.hideNotificationContent().get()) {
                updates.forEach { (libraryNovel, chapters) ->
                    val novel = libraryNovel.novel
                    val chapterNames = chapters.map { it.name }
                    notifications.add(
                        Pair(
                            context.notification(Notifications.CHANNEL_NEW_NOVEL_CHAPTERS) {
                                setSmallIcon(R.drawable.ic_yokai)
                                try {
                                    val request = ImageRequest.Builder(context).data(novel.thumbnailUrl)
                                        .networkCachePolicy(CachePolicy.DISABLED)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .transformations(CircleCropTransformation())
                                        .size(width = ICON_SIZE, height = ICON_SIZE).build()

                                    context.imageLoader
                                        .execute(request).image?.asDrawable(context.resources)?.let { drawable ->
                                            setLargeIcon((drawable as? BitmapDrawable)?.bitmap)
                                        }
                                } catch (_: Exception) {
                                }
                                setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                                setContentTitle(novel.title)
                                color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
                                val chaptersText = if (chapterNames.size > MAX_CHAPTERS) {
                                    "${chapterNames.take(MAX_CHAPTERS - 1).joinToString(", ")}, " +
                                        context.getString(
                                            MR.plurals.notification_and_n_more,
                                            (chapterNames.size - (MAX_CHAPTERS - 1)),
                                            (chapterNames.size - (MAX_CHAPTERS - 1)),
                                        )
                                } else {
                                    chapterNames.joinToString(", ")
                                }
                                setContentText(chaptersText)
                                setStyle(NotificationCompat.BigTextStyle().bigText(chaptersText))
                                priority = NotificationCompat.PRIORITY_HIGH
                                setGroup(Notifications.GROUP_NEW_NOVEL_CHAPTERS)
                                setContentIntent(getNotificationIntent())
                                setAutoCancel(true)
                            },
                            (novel.id ?: 0L).hashCode(),
                        ),
                    )
                }
            }

            NotificationManagerCompat.from(context).apply {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return@apply
                }
                notify(
                    Notifications.ID_NEW_NOVEL_CHAPTERS,
                    context.notification(Notifications.CHANNEL_NEW_NOVEL_CHAPTERS) {
                        setSmallIcon(R.drawable.ic_yokai)
                        setLargeIcon(notificationBitmap)
                        setContentTitle(context.getString(MR.strings.new_chapters_found))
                        color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
                        if (updates.size > 1) {
                            setContentText(
                                context.getString(
                                    MR.plurals.for_n_titles,
                                    updates.size,
                                    updates.size,
                                ),
                            )
                            if (!preferences.hideNotificationContent().get()) {
                                setStyle(
                                    NotificationCompat.BigTextStyle()
                                        .bigText(
                                            updates.keys.joinToString("\n") { it.novel.title.chop(45) },
                                        ),
                                )
                            }
                        } else if (!preferences.hideNotificationContent().get()) {
                            setContentText(updates.keys.first().novel.title.chop(45))
                        }
                        priority = NotificationCompat.PRIORITY_HIGH
                        setGroup(Notifications.GROUP_NEW_NOVEL_CHAPTERS)
                        setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                        setGroupSummary(true)
                        setContentIntent(getNotificationIntent())
                        setAutoCancel(true)
                    },
                )

                notifications.forEach { notify(it.second, it.first) }
            }
        }
    }

    fun showQueueSizeWarningNotification() {
        val notification = context.notificationBuilder(Notifications.CHANNEL_NOVEL_LIBRARY_PROGRESS) {
            setContentTitle(context.getString(MR.strings.warning))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(MR.strings.notification_size_warning)))
            setContentIntent(NotificationHandler.openUrl(context, HELP_WARNING_URL))
            setTimeoutAfter(30000)
        }.build()

        context.notificationManager.notify(
            Notifications.ID_NOVEL_LIBRARY_SIZE_WARNING,
            notification,
        )
    }

    fun cancelProgressNotification() {
        context.notificationManager.cancel(Notifications.ID_NOVEL_LIBRARY_PROGRESS)
    }

    /**
     * Plain MainActivity intent; no novel-side `SHORTCUT_RECENTLY_UPDATED` analogue exists yet.
     * When the Novels tab lands in Phase 7E, swap this for a novel-recents deep link.
     */
    private fun getNotificationIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val MAX_CHAPTERS = 5
        private const val TITLE_MAX_LEN = 45
        private const val ICON_SIZE = 192
        const val HELP_SKIPPED_URL = "https://tachiyomi.org/docs/faq/library#why-is-global-update-skipping-entries"
        const val HELP_WARNING_URL = "https://tachiyomi.org/docs/faq/library#why-am-i-warned-about-large-bulk-updates-and-downloads"
    }
}
