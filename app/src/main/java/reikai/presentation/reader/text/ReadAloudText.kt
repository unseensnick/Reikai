package reikai.presentation.reader.text

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Html
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.UnderlineSpan
import org.xml.sax.XMLReader

/**
 * A paragraph of one chunk, as read-aloud counts it (`ReadAloudSurface`). [start] and [end] bound its
 * shown characters inside chunk [chunk]'s text; a paragraph never spans chunks, since a chunk ends
 * where a line does.
 */
internal data class ChunkParagraph(val text: String, val chunk: Int, val start: Int, val end: Int)

/** Every paragraph of a chapter's chunks, in reading order. */
internal fun readAloudParagraphs(chunks: List<CharSequence>): List<ChunkParagraph> =
    chunks.flatMapIndexed { index, chunk ->
        val paragraphs = mutableListOf<ChunkParagraph>()
        var lineStart = 0
        for (i in 0..chunk.length) {
            if (i < chunk.length && chunk[i] != '\n') continue
            paragraphOf(chunk, index, lineStart, i)?.let(paragraphs::add)
            lineStart = i + 1
        }
        paragraphs
    }

private fun paragraphOf(chunk: CharSequence, index: Int, lineStart: Int, lineEnd: Int): ChunkParagraph? {
    val readings = (chunk as? Spanned)?.getSpans(lineStart, lineEnd, RubyReadingSpan::class.java)
        ?.map { chunk.getSpanStart(it) until chunk.getSpanEnd(it) }
        .orEmpty()
    val text = StringBuilder()
    var first = -1
    var last = -1
    var spaceOwed = false
    for (i in lineStart until lineEnd) {
        val c = chunk[i]
        if (c == OBJECT_REPLACEMENT || readings.any { i in it }) continue
        if (c.isReadAloudSpace()) {
            spaceOwed = text.isNotEmpty()
            continue
        }
        if (spaceOwed) text.append(' ')
        spaceOwed = false
        text.append(c)
        if (first < 0) first = i
        last = i
    }
    if (first < 0) return null
    return ChunkParagraph(text.toString(), index, first, last + 1)
}

private const val OBJECT_REPLACEMENT = '\uFFFC'

/** JavaScript's `\s`, so a paragraph collapses the same spaces here as `reader.js` does in the page. */
private fun Char.isReadAloudSpace() =
    this in "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF" || this in '\u2000'..'\u200A'

/** A ruby reading (`rt`, `rp`): drawn with the text, never spoken as part of it. */
internal class RubyReadingSpan

/**
 * Marks what `Html.fromHtml` reads inside `rt` and `rp`, which it passes to a tag handler as unknown
 * tags and otherwise keeps as plain text. The open tag's position rides on the output as a mark span,
 * so the handler holds no state and one instance serves every render.
 */
internal object RubyReadingTagHandler : Html.TagHandler {

    private class Open

    override fun handleTag(opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader) {
        if (!tag.equals("rt", ignoreCase = true) && !tag.equals("rp", ignoreCase = true)) return
        if (opening) {
            output.setSpan(Open(), output.length, output.length, Spanned.SPAN_MARK_MARK)
            return
        }
        val open = output.getSpans(0, output.length, Open::class.java).lastOrNull() ?: return
        val start = output.getSpanStart(open)
        output.removeSpan(open)
        if (start < output.length) {
            output.setSpan(RubyReadingSpan(), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/** Every span the read-aloud mark is drawn with, so clearing it finds them whatever copied the text. */
internal interface ReadAloudMark

internal class MarkBackgroundSpan(color: Int) : BackgroundColorSpan(color), ReadAloudMark

internal class MarkForegroundSpan(color: Int) : ForegroundColorSpan(color), ReadAloudMark

internal class MarkUnderlineSpan : UnderlineSpan(), ReadAloudMark

/**
 * A rounded box around the whole paragraph, drawn line by line. Each line clips a box that runs past
 * it on any side the paragraph continues, so only the paragraph's own ends get a top, a bottom and
 * corners. A line background rather than a metric-affecting span, which a precomputed text rejects.
 */
internal class MarkOutlineSpan(
    private val color: Int,
    private val strokePx: Float,
    private val radiusPx: Float,
) : LineBackgroundSpan, ReadAloudMark {

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
        val spanned = text as? Spanned ?: return
        val first = start <= spanned.getSpanStart(this)
        val last = end >= spanned.getSpanEnd(this)
        val half = strokePx / 2
        val style = paint.style
        val paintColor = paint.color
        val width = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = strokePx
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.drawRoundRect(
            left + half,
            if (first) top + half else top - radiusPx * 2,
            right - half,
            if (last) bottom - half else bottom + radiusPx * 2,
            radiusPx,
            radiusPx,
            paint,
        )
        canvas.restore()
        paint.style = style
        paint.color = paintColor
        paint.strokeWidth = width
    }
}
