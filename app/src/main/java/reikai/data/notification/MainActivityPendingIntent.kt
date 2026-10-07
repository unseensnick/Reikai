package reikai.data.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.main.MainActivity

/**
 * A notification tap that brings the app forward on [action] (one of the `Constants.SHORTCUT_*`, or
 * none to just open it). A PendingIntent's identity ignores extras, so callers that post the same
 * action with different [extras] need their own [requestCode].
 */
fun mainActivityPendingIntent(
    context: Context,
    action: String?,
    requestCode: Int = 0,
    extras: Intent.() -> Unit = {},
): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        this.action = action
        extras()
    }
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
