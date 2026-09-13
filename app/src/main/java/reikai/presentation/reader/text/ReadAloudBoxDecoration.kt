package reikai.presentation.reader.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Draws the read-aloud box behind the text: a band over each line's glyphs, or one outline around the
 * paragraph. A span cannot, since the framework hands a background span the whole line box with its
 * line and paragraph spacing, and neither can the chunk view, which clips what it draws to its bounds and
 * so cut the outline's pad off a paragraph at a chunk's or a chapter's edge. The list draws its
 * decorations before its items, so the box sits under the text and over the list's background only.
 * Measured from the chunk's live layout on every draw, which a restyle, an image or a scroll leaves right.
 */
internal class ReadAloudBoxDecoration(private val spoken: () -> Box?) : RecyclerView.ItemDecoration() {

    /** Characters [start] to [end] of [view]'s text, filled or stroked as [style] says. */
    class Box(val view: TextView, val start: Int, val end: Int, val color: Int, val style: Paint.Style)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontPaint = TextPaint()
    private val fontMetrics = Paint.FontMetricsInt()
    private val lineBox = RectF()
    private val outline = RectF()

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val box = spoken() ?: return
        val view = box.view
        val text = view.text as? Spanned ?: return
        val layout = view.layout ?: return
        if (box.start >= box.end) return
        // A chunk scrolled out of the list, or cached off it, has nowhere on screen to be drawn.
        var left = view.totalPaddingLeft
        var top = view.totalPaddingTop
        var current: View = view
        while (current !== parent) {
            left += current.left
            top += current.top
            current = current.parent as? View ?: return
        }
        boxPaint.color = box.color
        boxPaint.style = box.style
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        outline.setEmpty()
        for (line in layout.getLineForOffset(box.start)..layout.getLineForOffset(box.end - 1)) {
            if (!measureLine(view, layout, text, line, box.start, box.end)) continue
            if (box.style == Paint.Style.FILL) canvas.drawRect(lineBox, boxPaint) else outline.union(lineBox)
        }
        if (!outline.isEmpty) drawOutline(canvas, view)
        canvas.restore()
    }

    /**
     * Puts in [lineBox] the part of [start] to [end] that [line] holds: across those characters, and
     * from the ascent to the descent of the fonts they are drawn in. False when the line holds none.
     */
    private fun measureLine(view: TextView, layout: Layout, text: Spanned, line: Int, start: Int, end: Int): Boolean {
        val from = maxOf(start, layout.getLineStart(line))
        val visibleEnd = layout.getLineVisibleEnd(line)
        val to = minOf(end, visibleEnd)
        if (from >= to) return false
        // Taken from the line's own edge where the text reaches it: getPrimaryHorizontal measures
        // without justification, and an offset at a wrap with no space belongs to the next line.
        val endX = when {
            to < visibleEnd -> layout.getPrimaryHorizontal(to)
            layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT -> layout.getLineLeft(line)
            else -> layout.getLineRight(line)
        }
        val startX = layout.getPrimaryHorizontal(from)
        var ascent = 0
        var descent = 0
        var run = from
        while (run < to) {
            val next = text.nextSpanTransition(run, to, MetricAffectingSpan::class.java)
            fontPaint.set(view.paint)
            text.getSpans(run, next, MetricAffectingSpan::class.java).forEach { it.updateMeasureState(fontPaint) }
            fontPaint.getFontMetricsInt(fontMetrics)
            ascent = minOf(ascent, fontMetrics.ascent)
            descent = maxOf(descent, fontMetrics.descent)
            run = next
        }
        val baseline = layout.getLineBaseline(line).toFloat()
        lineBox.set(minOf(startX, endX), baseline + ascent, maxOf(startX, endX), baseline + descent)
        return true
    }

    /** The page's `.rk-tts-outline`: its outer edge [OUTLINE_PAD_DP] out from the text, stroke inside that. */
    private fun drawOutline(canvas: Canvas, view: View) {
        val density = view.resources.displayMetrics.density
        val stroke = OUTLINE_STROKE_DP * density
        outline.inset(-OUTLINE_PAD_DP * density + stroke / 2, -OUTLINE_PAD_DP * density + stroke / 2)
        boxPaint.strokeWidth = stroke
        val radius = (OUTLINE_RADIUS_DP * density - stroke / 2).coerceAtLeast(0f)
        canvas.drawRoundRect(outline, radius, radius, boxPaint)
    }

    private companion object {
        /** `reader.js`'s `OUTLINE_PAD_PX` and `reader.css`'s `.rk-tts-outline`, a CSS pixel being a dp. */
        const val OUTLINE_PAD_DP = 4f
        const val OUTLINE_STROKE_DP = 2f
        const val OUTLINE_RADIUS_DP = 6f
    }
}
