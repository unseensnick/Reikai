package exh.eh

import android.content.Context
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

/**
 * Ongoing progress notification for the E-Hentai gallery update checker. The "new chapters"
 * result is delivered through Mihon's [eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier],
 * since enhanced galleries are manga.
 */
class EHentaiUpdateNotifier(private val context: Context) {

    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_EHENTAI) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    fun showProgressNotification(manga: Manga, current: Int, total: Int) {
        context.notificationManager.notify(
            Notifications.ID_EHENTAI_PROGRESS,
            progressNotificationBuilder
                .setContentText(manga.title.take(40))
                .setProgress(total, current, false)
                .build(),
        )
    }

    fun cancelProgressNotification() {
        context.notificationManager.cancel(Notifications.ID_EHENTAI_PROGRESS)
    }
}
