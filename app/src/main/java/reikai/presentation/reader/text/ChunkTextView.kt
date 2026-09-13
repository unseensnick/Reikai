package reikai.presentation.reader.text

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView

/**
 * A chunk of a chapter's text, drawing the read-aloud box ([ReadAloudBoxSpan]) behind its glyphs. A
 * span cannot draw it: the framework hands a background span the whole line box, which takes in the
 * line spacing and the paragraph spacing, so the box is measured here from the layout, over what
 * the page's highlight covers. Plain `TextView` rather than AppCompat's, as the chunks always were.
 */
@SuppressLint("AppCompatCustomView")
internal class ChunkTextView(context: Context) : TextView(context) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontPaint = TextPaint()
    private val fontMetrics = Paint.FontMetricsInt()
    private val lineBox = RectF()
    private val outline = RectF()

    override fun onDraw(canvas: Canvas) {
        drawReadAloudBox(canvas)
        super.onDraw(canvas)
    }

    private fun drawReadAloudBox(canvas: Canvas) {
        val text = text as? Spanned ?: return
        val layout = layout ?: return
        val box = text.getSpans(0, text.length, ReadAloudBoxSpan::class.java).firstOrNull() ?: return
        val start = text.getSpanStart(box)
        val end = text.getSpanEnd(box)
        if (start >= end) return
        boxPaint.color = box.color
        boxPaint.style = box.style
        canvas.save()
        // Where TextView.onDraw puts the layout.
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        outline.setEmpty()
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
            if (!measureLine(layout, text, line, start, end)) continue
            if (box.style == Paint.Style.FILL) canvas.drawRect(lineBox, boxPaint) else outline.union(lineBox)
        }
        if (!outline.isEmpty) drawOutline(canvas)
        canvas.restore()
    }

    /**
     * Puts in [lineBox] the part of [start] to [end] that [line] holds: across those characters, and
     * from the ascent to the descent of the fonts they are drawn in. False when the line holds none.
     */
    private fun measureLine(layout: Layout, text: Spanned, line: Int, start: Int, end: Int): Boolean {
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
            fontPaint.set(paint)
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
    private fun drawOutline(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val stroke = OUTLINE_STROKE_DP * density
        outline.inset(-OUTLINE_PAD_DP * density, -OUTLINE_PAD_DP * density)
        // Held inside the view, which clips what it draws past its bounds: a paragraph opening a chunk
        // would otherwise lose its top edge.
        outline.intersect(
            -totalPaddingLeft.toFloat(),
            -totalPaddingTop.toFloat(),
            (width - totalPaddingLeft).toFloat(),
            (height - totalPaddingTop).toFloat(),
        )
        outline.inset(stroke / 2, stroke / 2)
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
