package reikai.presentation.reader.text

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * A chapter picture's place while it loads, drawn as the WebView page draws one (`img[src]:not(.rk-loaded)` in
 * reader.css): a rounded box in the text colour, so it sits in any reader theme. Whoever sets [pulse]
 * redraws the view, since a drawable in a span has no callback to redraw itself.
 */
internal class ImageLoadingDrawable(
    width: Int,
    height: Int,
    private val cornerPx: Float,
    private val textColor: () -> Int,
) : Drawable() {

    var pulse = PULSE_MAX

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setBounds(0, 0, width, height)
    }

    override fun draw(canvas: Canvas) {
        paint.color = ColorUtils.setAlphaComponent(textColor(), (BOX_ALPHA * pulse * 255).roundToInt())
        rect.set(bounds)
        canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The box's strength [elapsedMs] into loading: the details skeleton's pulse, eased there and back. */
internal fun imageLoadingPulse(elapsedMs: Long): Float {
    val phase = (elapsedMs % (PULSE_HALF_MS * 2)).toFloat() / PULSE_HALF_MS
    val eased = (1 - cos(PI * phase).toFloat()) / 2
    return PULSE_MIN + (PULSE_MAX - PULSE_MIN) * eased
}

// EntryDetailsSkeleton's range and period; the page's reader.css matches them.
private const val PULSE_MIN = 0.45f
private const val PULSE_MAX = 0.9f
private const val PULSE_HALF_MS = 900L
private const val BOX_ALPHA = 0.2f
