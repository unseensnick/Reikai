package exh.eh

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.math.RoundingMode
import java.text.NumberFormat

/**
 * Ongoing progress notification for the E-Hentai gallery update checker. The "new chapters"
 * result is delivered through Mihon's [eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier],
 * since enhanced galleries are manga. Styling mirrors that library notifier so the two read the same.
 */
class EHentaiUpdateNotifier(private val context: Context) {

    private val securityPreferences: SecurityPreferences by injectLazy()

    private val percentFormatter = NumberFormat.getPercentInstance().apply {
        roundingMode = RoundingMode.DOWN
        maximumFractionDigits = 0
    }

    private val notificationBitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_EHENTAI) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setLargeIcon(notificationBitmap)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    fun showProgressNotification(manga: Manga, current: Int, total: Int) {
        progressNotificationBuilder
            .setContentTitle(
                context.stringResource(
                    MR.strings.notification_updating_progress,
                    percentFormatter.format(current.toFloat() / total),
                ),
            )

        // Drop the gallery title too when adult-content notifications are hidden (EH is always adult).
        if (!securityPreferences.hideNotificationContent.get() &&
            !securityPreferences.hideAdultNotificationContent.get()
        ) {
            progressNotificationBuilder.setStyle(
                NotificationCompat.BigTextStyle().bigText(manga.title.chop(40)),
            )
        }

        context.notificationManager.notify(
            Notifications.ID_EHENTAI_PROGRESS,
            progressNotificationBuilder
                .setProgress(total, current, false)
                .build(),
        )
    }

    /**
     * Shows a notification for galleries that failed to update, tapping opens the full error log.
     *
     * @param failed number of galleries that failed to update.
     * @param uri error-log file listing every gallery that failed.
     */
    fun showUpdateErrorNotification(failed: Int, uri: Uri) {
        if (failed == 0) return

        context.notify(
            Notifications.ID_EHENTAI_ERROR,
            Notifications.CHANNEL_LIBRARY_EHENTAI,
        ) {
            setContentTitle(context.pluralStringResource(MR.plurals.notification_update_error, failed, failed))
            setContentText(context.stringResource(MR.strings.action_show_errors))
            setSmallIcon(R.drawable.ic_reikai)
            setLargeIcon(notificationBitmap)
            setContentIntent(NotificationReceiver.openErrorLogPendingActivity(context, uri))
        }
    }

    fun cancelProgressNotification() {
        context.notificationManager.cancel(Notifications.ID_EHENTAI_PROGRESS)
    }
}
