package reikai.presentation.reader.text

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * Puts a failed picture's words into its chunk's accessibility text. The box is drawn ([ImageFailureDrawable]),
 * so the text holds only the object replacement character there, which a screen reader skips: the node reads
 * the box's heading instead, and its Retry span, the link a screen reader offers, covers the Retry label.
 */
internal object FailedPictureAccessibility : View.AccessibilityDelegate() {

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        val shown = (host as? TextView)?.text as? Spanned ?: return
        val read = info.text ?: return
        // The node's text is a copy of the view's, so a box's place in one is its place in the other.
        if (read.length != shown.length) return
        val boxes = shown.getSpans(0, shown.length, ImageSpan::class.java).mapNotNull { span ->
            val box = (span.drawable as? DrawableWrapper)?.innerDrawable as? ImageFailureDrawable
            box?.let { Triple(shown.getSpanStart(span), shown.getSpanEnd(span), it) }
        }
        if (boxes.isEmpty()) return
        val text = SpannableStringBuilder(read)
        // From the end, so a replacement never moves a box still to come.
        boxes.sortedByDescending { it.first }.forEach { (start, end, box) ->
            val links = text.getSpans(start, end, ClickableSpan::class.java)
                .filter { text.getSpanStart(it) == start && text.getSpanEnd(it) == end }
            links.forEach(text::removeSpan)
            val words = listOfNotNull(box.heading, box.retryLabel).joinToString(". ")
            text.replace(start, end, words)
            val label = box.retryLabel ?: return@forEach
            val labelStart = start + words.length - label.length
            links.forEach { text.setSpan(it, labelStart, start + words.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        info.text = text
    }
}
