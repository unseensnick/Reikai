package reikai.presentation.reader.text

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import java.util.BitSet

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
    val (text, sources) = shownText(chunk, lineStart, lineEnd)
    if (text.isEmpty()) return null
    return ChunkParagraph(text, index, sources.first(), sources.last() + 1)
}

/**
 * Where [range] of this paragraph's [ChunkParagraph.text] sits in its chunk, as a start and an end, or
 * null when the range is not inside the text. [chunk] is the text of the chunk the paragraph names.
 */
internal fun ChunkParagraph.chunkRange(chunk: CharSequence, range: IntRange): Pair<Int, Int>? {
    val (shown, sources) = shownText(chunk, start, end)
    if (shown != text || range.isEmpty() || range.first < 0 || range.last >= sources.size) return null
    return sources[range.first] to sources[range.last] + 1
}

/**
 * The paragraph's text as read aloud, and for each of its characters the chunk offset it came from. A
 * collapsed space stands for the gap before the character after it, so it takes that character's offset.
 */
private fun shownText(chunk: CharSequence, lineStart: Int, lineEnd: Int): Pair<String, IntArray> {
    val readings = rubyCoverage(chunk, lineStart, lineEnd)
    val text = StringBuilder()
    val sources = mutableListOf<Int>()
    var spaceOwed = false
    for (i in lineStart until lineEnd) {
        val c = chunk[i]
        if (c == OBJECT_REPLACEMENT || readings[i]) continue
        if (c.isReadAloudSpace()) {
            spaceOwed = text.isNotEmpty()
            continue
        }
        if (spaceOwed) {
            text.append(' ')
            sources.add(i)
        }
        spaceOwed = false
        text.append(c)
        sources.add(i)
    }
    return text.toString() to sources.toIntArray()
}

/**
 * How many characters of [chunk] before [end] a reading line is counted in: every one read-aloud keeps
 * except its spaces. The page counts its text the same way (`reader.js`), so a line named by a count of
 * these is the same line in either renderer.
 */
internal fun shownCharCount(chunk: CharSequence, end: Int): Int {
    val readings = rubyCoverage(chunk, 0, end)
    return (0 until end).count { chunk.isCounted(it, readings) }
}

/** [shownCharCount] at every offset of [chunk] in one pass: entry `i` is the count before offset `i`. */
internal fun shownCharPrefix(chunk: CharSequence): IntArray {
    val readings = rubyCoverage(chunk, 0, chunk.length)
    val prefix = IntArray(chunk.length + 1)
    for (i in chunk.indices) prefix[i + 1] = prefix[i] + if (chunk.isCounted(i, readings)) 1 else 0
    return prefix
}

/** The offset in [chunk] of its counted character [index] (see [shownCharCount]), or null past its last. */
internal fun shownCharOffset(chunk: CharSequence, index: Int): Int? {
    if (index < 0) return null
    val readings = rubyCoverage(chunk, 0, chunk.length)
    var left = index
    for (i in chunk.indices) {
        if (!chunk.isCounted(i, readings)) continue
        if (left == 0) return i
        left--
    }
    return null
}

/** Which offsets of [chunk] a ruby reading covers, from the readings reaching into [start] until [end]. One
 *  pass over the spans, so each character's test is a lookup rather than a scan of every reading. */
private fun rubyCoverage(chunk: CharSequence, start: Int, end: Int): BitSet {
    val covered = BitSet()
    (chunk as? Spanned)?.getSpans(start, end, RubyReadingSpan::class.java)
        ?.forEach { covered.set(chunk.getSpanStart(it), chunk.getSpanEnd(it)) }
    return covered
}

private fun CharSequence.isCounted(i: Int, readings: BitSet): Boolean {
    val c = this[i]
    return c != OBJECT_REPLACEMENT && !c.isReadAloudSpace() && !readings[i]
}

private const val OBJECT_REPLACEMENT = '￼'

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
