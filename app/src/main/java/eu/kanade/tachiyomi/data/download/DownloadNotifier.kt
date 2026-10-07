package eu.kanade.tachiyomi.data.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import reikai.data.notification.downloadErrorTitle
import reikai.data.notification.hiddenEntryIds
import reikai.data.notification.isHiddenAdult
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.entry.withCustomInfo
import reikai.domain.manga.AdultContentChecker
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.util.regex.Pattern

/**
 * DownloadNotifier is used to show notifications when downloading one or multiple chapters.
 *
 * @param context context of application
 */
@Inject
class DownloadNotifier(
    private val context: Context,
    private val preferences: SecurityPreferences,
    private val getEntryCustomInfo: GetEntryCustomInfo, // RK
    // RK: deferred, so the extension manager behind it is only built once a download needs a verdict
    private val adultCheckerProvider: () -> AdultContentChecker,
) {

    private val adultChecker by lazy { adultCheckerProvider() } // RK

    // RK: names the entry by its Edit info title; the download folder keeps the source's
    private suspend fun shownTitle(manga: Manga): String =
        manga.withCustomInfo(getEntryCustomInfo.await(EntryId.Manga(manga.id))).title

    private val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setAutoCancel(false)
            setOnlyAlertOnce(true)
        }
    }

    /**
     * Status of download. Used for correct notification icon.
     */
    private var isDownloading = false

    /**
     * Shows a notification from this builder.
     *
     * @param id the id of the notification.
     */
    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    /**
     * Dismiss the downloader's notification. Downloader error notifications use a different id, so
     * those can only be dismissed by the user.
     */
    fun dismissProgress() {
        context.cancelNotification(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS)
        context.cancelNotification(Notifications.ID_DOWNLOAD_CHAPTER_PAUSED)
    }

    /**
     * Called when download progress changes.
     *
     * @param download download object containing download information.
     */
    // RK: suspends for the adult verdict, which keeps adult titles out of this notification too
    suspend fun onProgressChange(download: Download) {
        // RK -->
        val hidden = hiddenEntryIds(
            listOf(download.manga),
            preferences.hideNotificationContent.get(),
            preferences.hideAdultNotificationContent.get(),
            Manga::id,
            adultChecker::adultIdsAmong,
        ).isNotEmpty()
        // RK <--
        with(progressNotificationBuilder) {
            if (!isDownloading) {
                context.cancelNotification(Notifications.ID_DOWNLOAD_CHAPTER_PAUSED)
                setSmallIcon(android.R.drawable.stat_sys_download)
                clearActions()
                // Open download manager when clicked
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                isDownloading = true
                // Pause action
                addAction(
                    R.drawable.ic_pause_24dp,
                    context.stringResource(MR.strings.action_pause),
                    NotificationReceiver.pauseDownloadsPendingBroadcast(context),
                )
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openEntryPendingActivity(context, download.manga.id),
                )
            }

            val downloadingProgressText = context.stringResource(
                MR.strings.chapter_downloading_progress,
                download.downloadedImages,
                download.pages!!.size,
            )

            if (hidden) { // RK
                setContentTitle(downloadingProgressText)
                setContentText(null)
            } else {
                // RK: a chapter name repeats the source title, so that is what is stripped from it
                val title = shownTitle(download.manga).chop(15)
                val quotedTitle = Pattern.quote(download.manga.title.chop(15))
                val chapter = download.chapter.name.replaceFirst(
                    "$quotedTitle[\\s]*[-]*[\\s]*".toRegex(RegexOption.IGNORE_CASE),
                    "",
                )
                setContentTitle("$title - $chapter".chop(30))
                setContentText(downloadingProgressText)
            }

            setProgress(download.pages!!.size, download.downloadedImages, false)
            setOngoing(true)

            show(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS)
        }
    }

    /**
     * Show notification when download is paused.
     */
    // RK: [id] is the worker's own progress id for a network pause, see onNetworkPause, and [reason] why it waits
    fun onPaused(id: Int = Notifications.ID_DOWNLOAD_CHAPTER_PAUSED, reason: String? = null) {
        // The progress id belongs to the download worker's foreground service, which takes the
        // notification with it when the worker stops
        context.notify(id, pausedNotification(reason)) // RK

        // Reset initial values
        isDownloading = false
    }

    // RK: what onPaused shows, built apart so the waiting download worker's foreground notice is the same one
    fun pausedNotification(reason: String?): Notification =
        context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.chapter_paused))
            setContentText(reason ?: context.stringResource(MR.strings.download_notifier_download_paused)) // RK
            setSmallIcon(R.drawable.ic_pause_24dp)
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setOnlyAlertOnce(true)
            // Open download manager when clicked
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            // Resume action
            addAction(
                R.drawable.ic_play_arrow_24dp,
                context.stringResource(MR.strings.action_resume),
                NotificationReceiver.resumeDownloadsPendingBroadcast(context),
            )
            // Clear action
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel_all),
                NotificationReceiver.clearDownloadsPendingBroadcast(context),
            )
        }.build() // RK

    // RK -->

    /**
     * A network pause keeps the worker running, so its paused notice takes the worker's own id, and
     * replaces the one an earlier user pause left rather than sitting beside it. It says [reason], as the
     * novel drain's does.
     */
    fun onNetworkPause(reason: String) {
        context.cancelNotification(Notifications.ID_DOWNLOAD_CHAPTER_PAUSED)
        onPaused(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS, reason)
    }
    // RK <--

    /**
     * Resets the state once downloads are completed.
     */
    fun onComplete() {
        dismissProgress()

        // Reset states to default
        isDownloading = false
    }

    /**
     * Called when the downloader receives a warning.
     *
     * @param reason the text to show.
     * @param timeout duration after which to automatically dismiss the notification.
     * @param mangaId the id of the entry being warned about
     * Only works on Android 8+.
     */
    fun onWarning(reason: String, timeout: Long? = null, contentIntent: PendingIntent? = null, mangaId: Long? = null) {
        context.notify(
            Notifications.ID_DOWNLOAD_CHAPTER_ERROR,
            Notifications.CHANNEL_DOWNLOADER_ERROR,
        ) {
            setContentTitle(context.stringResource(MR.strings.download_notifier_downloader_title))
            setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setAutoCancel(true)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            if (mangaId != null) {
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openEntryPendingActivity(context, mangaId),
                )
            }
            timeout?.let { setTimeoutAfter(it) }
            contentIntent?.let { setContentIntent(it) }
        }

        // Reset download information
        isDownloading = false
    }

    /**
     * Called when the downloader receives an error. It's shown as a separate notification to avoid
     * being overwritten.
     *
     * @param error string containing error information.
     * @param chapter string containing chapter title.
     * RK: [manga] replaces upstream's mangaTitle and mangaId, for the adult check.
     * @param manga the entry that the error occurred on
     */
    // RK: takes the manga and suspends for the adult verdict, so adult titles stay out of errors too
    suspend fun onError(error: String? = null, chapter: String? = null, manga: Manga? = null) {
        // RK -->
        val hideAdult = preferences.hideAdultNotificationContent.get()
        val isAdult = isHiddenAdult(manga, hideAdult, Manga::id, adultChecker::adultIdsAmong)
        val title = downloadErrorTitle(manga?.let { shownTitle(it) }, chapter, hideAdult, isAdult)
        val mangaId = manga?.id
        // RK <--
        // Create notification
        context.notify(
            Notifications.ID_DOWNLOAD_CHAPTER_ERROR,
            Notifications.CHANNEL_DOWNLOADER_ERROR,
        ) {
            setContentTitle(
                title ?: context.stringResource(MR.strings.download_notifier_downloader_title), // RK
            )
            setContentText(error ?: context.stringResource(MR.strings.download_notifier_unknown_error))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            if (mangaId != null) {
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openEntryPendingActivity(context, mangaId),
                )
            }
        }

        // Reset download information
        isDownloading = false
    }
}
