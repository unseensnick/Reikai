package reikai.presentation.reader.text

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import logcat.LogPriority
import reikai.novel.font.NovelFontManager
import reikai.novel.font.isGenericFont
import reikai.novel.font.isSupportedFontFile
import reikai.presentation.reader.NovelReaderSettings
import reikai.presentation.reader.readerTextColorInt
import tachiyomi.core.common.util.system.logcat
import kotlin.math.roundToInt

/**
 * Applies the reader's display settings to a chunk view.
 *
 * Net-new rather than ported: tsundoku's renderer styles nothing, and the equivalent lives in the
 * host viewer we deliberately did not take, reading its own preferences directly. This reads the
 * settings the session already resolved, so the native renderer and the WebView answer to one
 * source. The bundled faces are the nine files under `assets/fonts`, which the WebView mode reads too.
 */
object NovelTextStyle {

    private val typefaceCache = HashMap<String, Typeface?>()

    fun apply(view: TextView, settings: NovelReaderSettings, context: Context, fontManager: NovelFontManager) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat())
        // Off, as tsundoku has it, so the text sits where the WebView puts it. It also lands at every
        // chunk seam here rather than once per chapter, since a chapter is split across views.
        view.includeFontPadding = false
        view.typeface = typefaceFor(context, fontManager, settings.fontFamily)
        val lineExtra = applyLineSpacing(view, settings.lineHeight)
        holdImagesOffLineSpacing(view)
        val density = context.resources.displayMetrics.density
        // The page margin goes on the sides only; the top and bottom belong to the column, or a chapter
        // split across chunk views would repeat it at every seam. The bottom takes the line spacing the
        // framework leaves off a layout's last line, so a chunk seam spaces like any other line.
        view.setPadding(
            (settings.margins.left * density).toInt(),
            0,
            (settings.margins.right * density).toInt(),
            lineExtra.coerceAtLeast(0f).roundToInt(),
        )
        val textColor = readerTextColorInt(settings.textColor)
        view.setTextColor(textColor)
        // Underlined in the text colour, as the WebView page draws a link, not the theme's accent.
        view.setLinkTextColor(textColor)
        applyAlignment(view, settings.textAlign)
    }

    /** The ends of the page, applied once to the column that holds the chunks. [topInsetPx] clears
     *  the display cutout on top of the margin, as the WebView page's own top padding does. */
    fun applyMargins(container: View, settings: NovelReaderSettings, context: Context, topInsetPx: Int) {
        val density = context.resources.displayMetrics.density
        container.setPadding(
            0,
            (settings.margins.top * density).toInt() + topInsetPx,
            0,
            (settings.margins.bottom * density).toInt(),
        )
    }

    /**
     * The setting is a multiple of the text size, as CSS `line-height` reads it, applied as the pixels
     * that take a line from the font's own height to that. A multiplier would scale a line holding an
     * image by the image's height. Against the font's height, which is larger, lines stood a quarter
     * further apart than the WebView page draws the same setting.
     * Requires the size and typeface to be set first. Returns the pixels added, negative when the
     * setting is tighter than the font's own height.
     */
    private fun applyLineSpacing(view: TextView, multiplier: Float): Float {
        val metrics = view.paint.fontMetricsInt
        val extra = multiplier * view.textSize - (metrics.descent - metrics.ascent)
        view.setLineSpacing(extra, 1f)
        return extra
    }

    /** A picture's line takes back the spacing the view adds under lines, as an img ignores line-height.
     *  Called on a restyle and before a render sets its text, since the span reads it at layout. */
    fun holdImagesOffLineSpacing(view: TextView) {
        val text = view.text as? Spanned ?: return
        val extra = view.lineSpacingExtra.roundToInt()
        text.getSpans(0, text.length, ChapterImageSpan::class.java).forEach { it.lineExtraPx = extra }
    }

    /** Justification is a paragraph property the framework only honours from API 26, our minimum. */
    private fun applyAlignment(view: TextView, align: String) {
        view.justificationMode = if (align == "justify") {
            Layout.JUSTIFICATION_MODE_INTER_WORD
        } else {
            Layout.JUSTIFICATION_MODE_NONE
        }
        view.gravity = when (align) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right" -> Gravity.END
            else -> Gravity.START
        }
    }

    /**
     * Four kinds of family share one preference: empty for the source's own, a generic CSS name, a
     * bundled asset key, or the file name of one the user added, which is the only one with a suffix.
     * The asset cache is here because a chapter builds one view per 6000 characters.
     */
    private fun typefaceFor(context: Context, fontManager: NovelFontManager, family: String): Typeface {
        if (family.isBlank()) return Typeface.DEFAULT
        if (isGenericFont(family)) {
            return when (family) {
                "serif" -> Typeface.SERIF
                "monospace" -> Typeface.MONOSPACE
                else -> Typeface.SANS_SERIF
            }
        }
        if (isSupportedFontFile(family)) {
            return fontManager.typeface(family) ?: Typeface.DEFAULT
        }
        val cached = typefaceCache.getOrPut(family) {
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$family.ttf") }
                .onFailure { logcat(LogPriority.WARN, it) { "Missing reader font asset: $family" } }
                .getOrNull()
        }
        return cached ?: Typeface.DEFAULT
    }
}
