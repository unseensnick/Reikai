package reikai.presentation.reader.text

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Html
import android.text.Spannable
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import android.text.style.ReplacementSpan
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.xml.sax.XMLReader
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The markup `Html.fromHtml` drops that the WebView page draws: rules, ruby set above its base, and
 * the targets of links within the chapter. [prepare] rewrites the document into tags this handler
 * reads, since a tag handler sees no attributes. The open tag's position rides on the output as a
 * mark span, so the handler holds no state and one instance serves every render.
 */
internal object NovelChapterTags : Html.TagHandler {

    /** The href a link to an anchor in the same chapter is rewritten to, followed by the anchor's index. */
    const val ANCHOR_HREF = "reikai-anchor:"

    private const val RULE_TAG = "rkrule"
    private const val ANCHOR_TAG = "rkanchor"

    /** Rules become [RULE_TAG], and each in-chapter link target gets an [ANCHOR_TAG] its link names. */
    fun prepare(doc: Document) {
        doc.select("hr").forEach { it.replaceWith(Element(RULE_TAG)) }
        var index = 0
        doc.select("a[href^=#]").forEach { link ->
            val name = link.attr("href").substring(1)
            val target = doc.getElementById(name)
                ?: doc.getElementsByAttributeValue("name", name).firstOrNull()
                ?: return@forEach
            val marker = Element("$ANCHOR_TAG$index")
            if (target.childNodeSize() > 0) target.prependChild(marker) else target.before(marker)
            link.attr("href", "$ANCHOR_HREF$index")
            index++
        }
    }

    private class Open(val tag: String)

    override fun handleTag(opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader) {
        val name = tag.lowercase()
        when {
            name == RULE_TAG -> if (!opening) appendRule(output)
            name.startsWith(ANCHOR_TAG) -> if (opening) {
                val index = name.removePrefix(ANCHOR_TAG).toIntOrNull() ?: return
                output.setSpan(AnchorSpan(index), output.length, output.length, Spanned.SPAN_MARK_MARK)
            }
            name == "rt" || name == "rp" || name == "ruby" -> if (opening) {
                output.setSpan(Open(name), output.length, output.length, Spanned.SPAN_MARK_MARK)
            } else {
                close(name, output)
            }
        }
    }

    private fun appendRule(output: Editable) {
        if (output.isNotEmpty() && output[output.length - 1] != '\n') output.append('\n')
        // A no-break space: a line of its own to draw across, which read-aloud counts as blank.
        output.append(" \n")
        output.setSpan(RuleMark(), output.length - 2, output.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Draws each rule marked while parsing. Placed only once the blank lines are collapsed, since a
     * paragraph span set while parsing is squeezed to nothing by those deletions and draws no rule.
     */
    fun placeRules(text: Spannable) {
        text.getSpans(0, text.length, RuleMark::class.java).forEach { mark ->
            val start = text.getSpanStart(mark)
            text.removeSpan(mark)
            val end = if (start + 1 < text.length && text[start + 1] == '\n') start + 2 else start + 1
            text.setSpan(RuleSpan(), start, end, Spanned.SPAN_PARAGRAPH)
        }
    }

    private fun close(name: String, output: Editable) {
        val open = output.getSpans(0, output.length, Open::class.java).lastOrNull { it.tag == name } ?: return
        val start = output.getSpanStart(open)
        output.removeSpan(open)
        if (start >= output.length) return
        when (name) {
            "rt" -> {
                output.setSpan(RubyReadingSpan(), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                output.setSpan(RubyTextMark(), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "rp" -> output.setSpan(RubyReadingSpan(), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "ruby" -> setRuby(output, start, output.length)
        }
    }

    /** Pairs each reading with the base text before it, the parentheses a `rp` adds left undrawn. */
    private fun setRuby(output: Editable, start: Int, end: Int) {
        val readings = output.getSpans(start, end, RubyTextMark::class.java).sortedBy(output::getSpanStart)
        val hidden = output.getSpans(start, end, RubyReadingSpan::class.java)
            .map { output.getSpanStart(it) until output.getSpanEnd(it) }
        var cursor = start
        readings.forEach { reading ->
            val readingStart = output.getSpanStart(reading)
            var spanEnd = output.getSpanEnd(reading)
            hidden.firstOrNull { it.first == spanEnd }?.let { spanEnd = it.last + 1 }
            val base = (cursor until readingStart).filter { i -> hidden.none { i in it } }
                .joinToString("") { output[it].toString() }
            if (base.isNotBlank()) {
                val text = output.subSequence(readingStart, output.getSpanEnd(reading)).toString()
                output.setSpan(RubySpan(base, text.trim()), cursor, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cursor = spanEnd
        }
    }
}

/** The place a link within the chapter scrolls to, numbered as [NovelChapterTags.prepare] numbered it. */
internal class AnchorSpan(val index: Int)

/** The no-break space a rule is drawn across, until [NovelChapterTags.placeRules] draws it. */
private class RuleMark

/** Which reading chars are a `rt`'s, as opposed to a `rp`'s fallback parentheses. */
private class RubyTextMark

/** A rule across the column, in the text colour at the WebView page's opacity. */
internal class RuleSpan : LineBackgroundSpan {
    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val color = paint.color
        val style = paint.style
        val y = (top + baseline + paint.fontMetricsInt.descent) / 2f
        paint.color = color and 0x00FFFFFF or (RULE_ALPHA shl 24)
        paint.style = Paint.Style.FILL
        canvas.drawRect(left.toFloat(), y, right.toFloat(), y + max(1f, paint.textSize / 16f), paint)
        paint.color = color
        paint.style = style
    }

    private companion object {
        /** `opacity: 0.3` in reader.css. */
        const val RULE_ALPHA = 77
    }
}

/**
 * [base] with [reading] centred above it at [READING_SCALE] of its size, as a browser sets ruby. The
 * line grows by the reading's height, as it does in the page.
 */
internal class RubySpan(private val base: String, private val reading: String) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val baseWidth = paint.measureText(base)
        val readingWidth = withReadingSize(paint) { it.measureText(reading) }
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val lift = withReadingSize(paint) { it.fontMetricsInt.let { m -> m.descent - m.ascent } }
            fm.ascent -= lift
            fm.top -= lift
        }
        return max(baseWidth, readingWidth).roundToInt()
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
        val baseWidth = paint.measureText(base)
        val width = max(baseWidth, withReadingSize(paint) { it.measureText(reading) })
        val baseAscent = paint.fontMetricsInt.ascent
        canvas.drawText(base, x + (width - baseWidth) / 2, y.toFloat(), paint)
        withReadingSize(paint) {
            val readingY = y + baseAscent - it.fontMetricsInt.descent
            canvas.drawText(reading, x + (width - it.measureText(reading)) / 2, readingY.toFloat(), it)
        }
    }

    private inline fun <T> withReadingSize(paint: Paint, block: (Paint) -> T): T {
        val size = paint.textSize
        paint.textSize = size * READING_SCALE
        return try {
            block(paint)
        } finally {
            paint.textSize = size
        }
    }

    private companion object {
        /** A browser's default `rt` size. */
        const val READING_SCALE = 0.5f
    }
}
