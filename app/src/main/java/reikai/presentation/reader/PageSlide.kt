package reikai.presentation.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView

/**
 * Slides a paged novel's turn the way manga's pager does. A page is a slice of one column, with no next page
 * beside it to drag in, so the page on screen is copied, laid over [content] while the turn lands under it, and
 * the copy slides out as the drawn new page slides in. Both renderers turn through this, so their turns match.
 */
class PageSlide(private val content: View) {

    private val handler = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null
    private var cover: ImageView? = null
    private var leftward = true
    private var capturing = false

    // A turn whose page never reports drawn (a failed load, a turn that went nowhere) must not leave the old page up.
    private val giveUp = Runnable(::finish)

    /**
     * Copies the page on screen, then runs [turn] under the copy; [leftward] is the way the pages move. A turn
     * arriving while one slides ends that one first, and with no window or page to copy it turns at once.
     */
    fun turn(leftward: Boolean, turn: () -> Unit) {
        finish()
        val window = (content.context as? Activity)?.window
        val parent = content.parent as? ViewGroup
        if (capturing || window == null || parent == null || content.width == 0 || content.height == 0) {
            turn()
            return
        }
        val copy = bitmap?.takeIf { it.width == content.width && it.height == content.height }
            ?: Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val at = IntArray(2).also(content::getLocationInWindow)
        capturing = true
        // PixelCopy reads what the screen shows, which a WebView's own draw into a bitmap does not reliably give.
        try {
            PixelCopy.request(
                window,
                Rect(at[0], at[1], at[0] + content.width, at[1] + content.height),
                copy,
                { result ->
                    capturing = false
                    // Uploads the copy while the turn lands, rather than on the slide's first frame.
                    copy.prepareToDraw()
                    if (result == PixelCopy.SUCCESS && content.isAttachedToWindow) lay(parent, copy, leftward)
                    turn()
                },
                handler,
            )
        } catch (_: IllegalArgumentException) {
            // Thrown once the window has no surface, which a web turn waiting on its page can reach after the
            // reader left the screen. The turn lands without its slide.
            capturing = false
            turn()
        }
    }

    /** The turned page is drawn: the copy slides off and the page on. Nothing to do without a copy up. */
    fun pageDrawn() {
        val copy = cover ?: return
        handler.removeCallbacks(giveUp)
        val width = content.width.toFloat()
        val out = if (leftward) -width else width
        content.translationX = -out
        content.animate().translationX(0f).setDuration(DURATION_MS).setInterpolator(EASE).start()
        copy.animate().translationX(out).setDuration(DURATION_MS).setInterpolator(EASE).withEndAction(::finish).start()
    }

    /** Ends a slide where it stands: the copy gone and the page back in place. */
    fun finish() {
        handler.removeCallbacks(giveUp)
        content.animate().cancel()
        content.translationX = 0f
        cover?.let {
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        cover = null
    }

    private fun lay(parent: ViewGroup, copy: Bitmap, leftward: Boolean) {
        this.leftward = leftward
        cover = ImageView(content.context).apply {
            setImageBitmap(copy)
            scaleType = ImageView.ScaleType.FIT_XY
        }.also {
            parent.addView(it, parent.indexOfChild(content) + 1, ViewGroup.LayoutParams(content.width, content.height))
        }
        handler.postDelayed(giveUp, GIVE_UP_MS)
    }

    internal companion object {
        private const val DURATION_MS = 250L
        const val GIVE_UP_MS = 1000L
        private val EASE = DecelerateInterpolator()
    }
}
