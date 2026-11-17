package reikai.presentation.reader.text

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.core.graphics.ColorUtils

/**
 * A failed chapter picture, drawn as the WebView page draws one (`.rk-image-failure` in reader.css): a
 * box saying so, and a Retry pill when the picture can be asked for again. The colour is read as it
 * draws, so a theme change reaches a box already on screen. Sized at creation, [em] being the text size.
 * The border, corner and opacity are the page's, held to it by NovelWebTwinValuesTest.
 */
internal class ImageFailureDrawable(
    width: Int,
    private val em: Float,
    private val density: Float,
    val heading: String,
    /** Null for a picture that cannot be asked for again, an inline one or an unsupported address. */
    val retryLabel: String?,
    private val textColor: () -> Int,
) : Drawable() {

    /** While a retry runs, the pill stands dimmed rather than taking a second tap. Whoever sets it redraws
     *  the view: a drawable in a span has no callback, so it cannot redraw itself. */
    var retrying = false

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = em }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // reader.css: 1.25em of padding, the heading's line, then 0.85em and the pill. The 1em above and below
    // is the picture's own margin, which ChapterImageSpan gives whatever the picture draws.
    private val padding = em * 1.25f
    private val lineHeight = textPaint.fontSpacing
    private val pillHeight = lineHeight + em

    init {
        val pill = if (retryLabel == null) 0f else em * 0.85f + pillHeight
        setBounds(0, 0, width, (padding * 2 + lineHeight + pill).toInt())
    }

    override fun draw(canvas: Canvas) {
        val color = textColor()
        val box = RectF(bounds)
        // The page's opacity is the whole box's, heading and pill included, so the box is drawn as one layer.
        val layer = canvas.saveLayerAlpha(box, (255 * BOX_ALPHA).toInt())
        drawBox(canvas, box, color)
        canvas.restoreToCount(layer)
    }

    private fun drawBox(canvas: Canvas, box: RectF, color: Int) {
        linePaint.color = color
        linePaint.strokeWidth = BORDER_DP * density
        // Inset by half the stroke, as CSS draws a border inside the box rather than across its edge.
        val outline = RectF(box).apply { inset(linePaint.strokeWidth / 2, linePaint.strokeWidth / 2) }
        canvas.drawRoundRect(outline, CORNER_DP * density, CORNER_DP * density, linePaint)

        textPaint.color = color
        textPaint.textAlign = Paint.Align.CENTER
        val headingBaseline = box.top + padding - textPaint.fontMetrics.ascent
        canvas.drawText(heading, box.centerX(), headingBaseline, textPaint)

        val label = retryLabel ?: return
        val pillTop = box.top + padding + lineHeight + em * 0.85f
        val pillWidth = textPaint.measureText(label) + em * 3
        val pill = RectF(box.centerX() - pillWidth / 2, pillTop, box.centerX() + pillWidth / 2, pillTop + pillHeight)
        pillPaint.color = if (retrying) ColorUtils.setAlphaComponent(color, RETRYING_ALPHA) else color
        canvas.drawRoundRect(pill, pillHeight / 2, pillHeight / 2, pillPaint)
        // The page draws the label in the background colour, which a drawable has no way to read, so it
        // takes whichever of black and white stands out on the pill.
        textPaint.color = if (ColorUtils.calculateLuminance(color) > 0.5) BLACK else WHITE
        canvas.drawText(label, pill.centerX(), pill.top + em / 2 - textPaint.fontMetrics.ascent, textPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val BOX_ALPHA = 0.85f
        const val BORDER_DP = 1f
        const val CORNER_DP = 8f
        private const val RETRYING_ALPHA = 96
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}
