package eu.kanade.tachiyomi.extension.api

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import reikai.data.notification.notifyExtensionUpdates
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.i18n.MR

@Inject
@SingleIn(AppScope::class)
class ExtensionUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
) {
    fun promptUpdates(names: List<String>) {
        // RK --> built by the notice light-novel plugins share
        context.notifyExtensionUpdates(
            Notifications.ID_UPDATES_TO_EXTS,
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
            context.pluralStringResource(
                MR.plurals.update_check_notification_ext_updates,
                names.size,
                names.size,
            ),
            names,
            securityPreferences.hideNotificationContent.get(),
        )
        // RK <--
    }

    fun dismiss() {
        context.cancelNotification(Notifications.ID_UPDATES_TO_EXTS)
    }
}
