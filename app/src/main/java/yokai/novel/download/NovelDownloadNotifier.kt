package yokai.novel.download

import android.app.Notification
import android.content.Context
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

/**
 * Foreground progress notification for the novel chapter downloader. Minimal sibling of
 * [eu.kanade.tachiyomi.data.library.NovelUpdateNotifier]: one ongoing progress entry, no per-novel
 * actions (there's no novel-downloads screen to deep-link into yet).
 */
class NovelDownloadNotifier(private val context: Context) {

    private val builder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_DOWNLOADER) {
            setSmallIcon(R.drawable.ic_file_download_24dp)
            setOngoing(true)
            setOnlyAlertOnce(true)
            color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
            addAction(
                R.drawable.ic_close_24dp,
                context.getString(AR.string.cancel),
                NotificationReceiver.cancelNovelDownloadPendingBroadcast(context),
            )
        }
    }

    /** Build the progress notification (also used for the worker's [getForegroundInfo]). */
    fun progress(title: String, current: Int, total: Int): Notification =
        builder
            .setContentTitle("${context.getString(MR.strings.downloads)} ($current/$total)")
            .setContentText(title)
            .setProgress(total, current, total == 0)
            .build()

    fun show(title: String, current: Int, total: Int) {
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOADER, progress(title, current, total))
    }

    fun dismiss() {
        context.notificationManager.cancel(Notifications.ID_NOVEL_DOWNLOADER)
    }
}
