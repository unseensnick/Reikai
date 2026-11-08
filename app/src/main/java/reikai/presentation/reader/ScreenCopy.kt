package reikai.presentation.reader

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.view.PixelCopy
import android.view.View

/**
 * Copies what the screen shows over [view] into [into], then calls [onCopied] on [handler] with whether it
 * worked. False when nothing was asked for: no Activity window, or one with no surface left, where PixelCopy
 * throws, which a web turn waiting on its page can reach after the reader left the screen.
 */
internal fun copyScreen(view: View, into: Bitmap, handler: Handler, onCopied: (copied: Boolean) -> Unit): Boolean {
    val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()?.window ?: return false
    val at = IntArray(2).also(view::getLocationInWindow)
    return try {
        // PixelCopy reads what the screen shows, which a WebView's own draw into a bitmap does not reliably give.
        PixelCopy.request(
            window,
            Rect(at[0], at[1], at[0] + view.width, at[1] + view.height),
            into,
            { result -> onCopied(result == PixelCopy.SUCCESS) },
            handler,
        )
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}
