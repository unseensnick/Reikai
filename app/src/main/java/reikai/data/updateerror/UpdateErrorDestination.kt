package reikai.data.updateerror

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.ui.main.MainActivity
import reikai.domain.library.ContentType
import tachiyomi.core.common.Constants

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
    updateErrorsScreenPendingIntent(context, type)
} else {
    NotificationReceiver.openErrorLogPendingActivity(context, log)
}

private fun updateErrorsScreenPendingIntent(context: Context, type: ContentType): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        action = Constants.SHORTCUT_UPDATE_ERRORS
        putExtra(Constants.CONTENT_TYPE_EXTRA, type.name)
    }
    // A PendingIntent's identity ignores extras, so one shared request code would leave both
    // notifications opening whichever content type posted last.
    return PendingIntent.getActivity(
        context,
        type.ordinal,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
