package reikai.presentation.reader.text

import android.text.Layout
import android.text.Spannable
import android.text.method.MovementMethod
import android.text.style.ClickableSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView

/**
 * Dispatches a tap to a link and does nothing else.
 *
 * `LinkMovementMethod` calls `Selection.setSelection`, which throws "Selection cancelled" on a
 * TextView that is not selectable, and it also swallows the taps the reader needs for its chrome.
 * Ported from tsundoku (`textview/LinkOnlyMovementMethod.kt`).
 */
object LinkOnlyMovementMethod : MovementMethod {
    override fun initialize(widget: TextView, text: Spannable) = Unit
    override fun onKeyDown(widget: TextView, text: Spannable, keyCode: Int, event: KeyEvent) = false
    override fun onKeyUp(widget: TextView, text: Spannable, keyCode: Int, event: KeyEvent) = false
    override fun onKeyOther(view: TextView, text: Spannable, event: KeyEvent) = false
    override fun onTrackballEvent(widget: TextView, text: Spannable, event: MotionEvent) = false
    override fun onGenericMotionEvent(widget: TextView, text: Spannable, event: MotionEvent) = false
    override fun canSelectArbitrarily() = false
    override fun onTakeFocus(widget: TextView, text: Spannable, direction: Int) = Unit

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) return false
        val layout = widget.layout ?: return false
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        // The offset snaps to the nearest boundary, so a tap in the blank past a line lands on a link
        // ending it. A tap follows a link only within its own run on that line, give or take half a
        // letter, about the slop the WebView page's touch adjustment grants one. A link wrapping past
        // either end runs to that edge, where the line's end offset already names the next line.
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        val rightToLeft = layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT
        val head = if (rightToLeft) layout.getLineRight(line) else layout.getLineLeft(line)
        val tail = if (rightToLeft) layout.getLineLeft(line) else layout.getLineRight(line)
        val slop = widget.textSize / 2
        val links = buffer.getSpans(offset, offset, ClickableSpan::class.java).filter { link ->
            val start = buffer.getSpanStart(link)
            val end = buffer.getSpanEnd(link)
            val from = if (start < lineStart) head else layout.getPrimaryHorizontal(start)
            val to = if (end >= lineEnd) tail else layout.getPrimaryHorizontal(end)
            x >= minOf(from, to) - slop && x <= maxOf(from, to) + slop
        }
        if (links.isEmpty()) return false
        if (action == MotionEvent.ACTION_UP) {
            // The view queued its own click before this ran, and the reader takes a click as a tap zone.
            widget.cancelPendingInputEvents()
            links[0].onClick(widget)
        }
        return true
    }
}
