package reikai.data.updateerror

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import reikai.data.notification.mainActivityPendingIntent
import reikai.domain.library.ContentType
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Where a failed-update notification's tap goes, decided once for both content types: the Update
 * errors screen when that type records its failures, the shared dump when it does not.
 */
fun updateErrorPendingIntent(
    context: Context,
    type: ContentType,
    log: Uri,
    tracked: Boolean,
): PendingIntent = if (tracked) {
    // One request code per type, or both notifications would open whichever type posted last.
    mainActivityPendingIntent(context, Constants.SHORTCUT_UPDATE_ERRORS, requestCode = type.ordinal) {
        putExtra(Constants.CONTENT_TYPE_EXTRA, type.name)
    }
} else {
    NotificationReceiver.openErrorLogPendingActivity(context, log)
}

/** What every failed-update notification says ("3 updates failed", "Show errors") and where its tap goes. */
fun NotificationCompat.Builder.setUpdateErrorContent(context: Context, failed: Int, contentIntent: PendingIntent) {
    setContentTitle(context.pluralStringResource(MR.plurals.notification_update_error, failed, failed))
    setContentText(context.stringResource(MR.strings.action_show_errors))
    setSmallIcon(R.drawable.ic_reikai)
    setAutoCancel(true)
    setContentIntent(contentIntent)
}
