package reikai.data.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.util.system.notify
import mihon.icons.materialsymbols.R as MaterialSymbolsR

/**
 * The update notice extensions and light-novel plugins share, each on its own channel and id: it
 * names what has an update unless Hide notification content is on, and opens Browse -> Extensions.
 */
fun Context.notifyExtensionUpdates(
    id: Int,
    channelId: String,
    title: String,
    names: List<String>,
    hideContent: Boolean,
) {
    notify(id, channelId) {
        setContentTitle(title)
        extensionUpdatesNoticeText(names, hideContent)?.let { text ->
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        setSmallIcon(MaterialSymbolsR.drawable.rounded_filled_extension)
        setContentIntent(NotificationReceiver.openExtensionsPendingActivity(this@notifyExtensionUpdates))
        setAutoCancel(true)
    }
}

internal fun extensionUpdatesNoticeText(names: List<String>, hideContent: Boolean): String? =
    if (hideContent) null else names.joinToString(", ")
