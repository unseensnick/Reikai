package reikai.presentation.reader.text

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan

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

/** Every span the read-aloud mark is drawn with, so clearing it finds them whatever copied the text. */
internal interface ReadAloudMark

internal class MarkForegroundSpan(color: Int) : ForegroundColorSpan(color), ReadAloudMark

/** Drawn by the framework at the font's underline position, which the line's spacing never moves. */
internal class MarkUnderlineSpan : UnderlineSpan(), ReadAloudMark
