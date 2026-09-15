package reikai.presentation.reader.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ImageSpan
import android.text.style.LineHeightSpan

/**
 * A chapter picture laid out as the WebView page lays out an `img` (reader.css): a block with [topPx]
 * above and [bottomPx] below, which the reader's line spacing does not reach. `Html.fromHtml`'s own span
 * draws from the line's bottom, so the spacing the framework adds under every line moved the picture, and
 * a spacing tighter than the font pulled it into the text above. This one draws from the line's top.
 */
internal class ChapterImageSpan(
    drawable: Drawable,
    source: String?,
    val topPx: Int,
    private val bottomPx: Int,
) :
    ImageSpan(drawable, source.orEmpty()),
    LineHeightSpan {

    /** The spacing the view adds under each line, taken back off this one. Set by [NovelTextStyle]. */
    @Volatile
    var lineExtraPx = 0

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        // Set outright rather than added to, so the newline sharing the line lends it no font height.
        fm.ascent = -(drawable.bounds.height() + topPx)
        fm.top = fm.ascent
        // A layout's last line gets no spacing added (NovelTextStyle pads the view with it instead).
        fm.descent = bottomPx - if (end >= text.length) 0 else lineExtraPx
        fm.bottom = fm.descent
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        canvas.save()
        canvas.translate(x, (top + topPx).toFloat())
        drawable.draw(canvas)
        canvas.restore()
    }
}
