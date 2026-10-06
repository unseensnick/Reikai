package reikai.presentation.reader.text

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import reikai.presentation.components.LoadingPulse
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

    var pulse = LoadingPulse.MAX

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

    companion object {
        // The box's strength at a full pulse; reader.css's opacities are this times the pulse range.
        const val BOX_ALPHA = 0.2f
    }
}

/** The box's strength [elapsedMs] into loading: [LoadingPulse], eased there and back. */
internal fun imageLoadingPulse(elapsedMs: Long): Float {
    val half = LoadingPulse.HALF_PERIOD_MS
    val phase = (elapsedMs % (half * 2)).toFloat() / half
    val eased = (1 - cos(PI * phase).toFloat()) / 2
    return LoadingPulse.MIN + (LoadingPulse.MAX - LoadingPulse.MIN) * eased
}
