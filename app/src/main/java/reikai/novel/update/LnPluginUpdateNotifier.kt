package reikai.novel.update

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import reikai.data.notification.notifyExtensionUpdates
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.i18n.MR

/**
 * The light-novel plugin half of Mihon's ExtensionUpdateNotifier and its pending count: the notice is
 * the one extensions post ([notifyExtensionUpdates]), and clears once nothing is pending, as Mihon's
 * ExtensionManager.updatePendingUpdatesCount clears the extension one.
 */
@Inject
@SingleIn(AppScope::class)
class LnPluginUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
    private val novelPreferences: NovelPreferences,
) {

    fun promptUpdates(names: List<String>) {
        context.notifyExtensionUpdates(
            Notifications.ID_LN_PLUGIN_UPDATES,
            Notifications.CHANNEL_LN_PLUGIN_UPDATE,
            context.pluralStringResource(MR.plurals.ln_plugin_updates_available, names.size, names.size),
            names,
            securityPreferences.hideNotificationContent.get(),
        )
    }

    /** Every writer of the Browse badge's plugin count goes through here, so none leaves a stale notice. */
    fun setPendingCount(count: Int) {
        novelPreferences.pluginUpdatesCount().set(count)
        if (count == 0) context.cancelNotification(Notifications.ID_LN_PLUGIN_UPDATES)
    }
}
