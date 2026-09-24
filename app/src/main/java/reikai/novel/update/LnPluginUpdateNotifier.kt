package reikai.novel.update

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * The light-novel plugin half of Mihon's ExtensionUpdateNotifier and its pending count: the notice
 * hides plugin names under Hide notification content, and clears once nothing is pending, as
 * Mihon's ExtensionManager.updatePendingUpdatesCount clears the extension one.
 */
@Inject
@SingleIn(AppScope::class)
class LnPluginUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
    private val novelPreferences: NovelPreferences,
) {

    fun promptUpdates(names: List<String>) {
        context.notify(Notifications.ID_LN_PLUGIN_UPDATES, Notifications.CHANNEL_LN_PLUGIN_UPDATE) {
            setContentTitle(context.stringResource(MR.strings.ln_plugins_update_available, names.size))
            pluginUpdateNoticeText(names, securityPreferences.hideNotificationContent.get())?.let { text ->
                setContentText(text)
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
            }
            setSmallIcon(R.drawable.ic_extension_24dp)
            setContentIntent(NotificationReceiver.openExtensionsPendingActivity(context))
            setAutoCancel(true)
        }
    }

    /** Every writer of the Browse badge's plugin count goes through here, so none leaves a stale notice. */
    fun setPendingCount(count: Int) {
        novelPreferences.pluginUpdatesCount().set(count)
        if (count == 0) context.cancelNotification(Notifications.ID_LN_PLUGIN_UPDATES)
    }
}

internal fun pluginUpdateNoticeText(names: List<String>, hideContent: Boolean): String? =
    if (hideContent) null else names.joinToString(", ")
