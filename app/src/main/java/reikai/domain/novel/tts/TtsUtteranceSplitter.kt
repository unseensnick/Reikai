package reikai.domain.novel.tts

import java.text.BreakIterator
import java.util.Locale

/** A piece of a paragraph to speak, and where it sits in that paragraph: [start] until [end]. */
data class TtsPiece(val text: String, val start: Int, val end: Int)

/**
 * Breaks a paragraph into pieces a speech engine will speak. An utterance past the engine's maximum
 * fails, often later through `onError` rather than at `speak`, so a long paragraph goes out as several.
 *
 * Built on [BreakIterator] rather than on a punctuation list: its sentence instance is locale
 * correct, and its line instance segments Chinese, Japanese, Thai and Khmer by dictionary, which is
 * the text that has no spaces to break at. The cap is never exceeded, whatever the text.
 */
object TtsUtteranceSplitter {

    /**
     * The pieces of [text] with their offsets in it. [bySentence] gives every sentence a piece of its own,
     * so the one being spoken can be marked; otherwise sentences are packed up to [maxLength].
     */
    fun pieces(text: String, maxLength: Int, locale: Locale, bySentence: Boolean): List<TtsPiece> {
        val lead = text.indexOfFirst { it > ' ' }
        if (maxLength <= 0 || lead < 0) return emptyList()
        val trail = text.indexOfLast { it > ' ' } + 1
        if (!bySentence && trail - lead <= maxLength) return listOf(TtsPiece(text.substring(lead, trail), lead, trail))

        val chunks = mutableListOf<TtsPiece>()
        // The chunk being packed, as a span of text: segments are contiguous, so a start and an end hold it.
        var packedStart = -1
        var packedEnd = -1

        fun flush() {
            if (packedStart >= 0) trimmed(text, packedStart, packedEnd)?.let(chunks::add)
            packedStart = -1
        }

        // Appends what fits and starts a new chunk with what does not. A piece bigger than the cap on
        // its own is one unbreakable run, which only the cut below can shorten.
        fun append(start: Int, end: Int) {
            if (packedStart >= 0 && packedEnd - packedStart + (end - start) > maxLength) flush()
            if (end - start <= maxLength) {
                if (packedStart < 0) packedStart = start
                packedEnd = end
            } else {
                flush()
                chunks.addAll(cut(text, start, end, maxLength))
            }
        }

        for ((start, end) in segments(BreakIterator.getSentenceInstance(locale), text, lead, trail)) {
            if (end - start <= maxLength) {
                append(start, end)
            } else {
                // Only now, because a sentence that fits should stay whole even where it could break.
                flush()
                segments(BreakIterator.getLineInstance(locale), text, start, end).forEach { (s, e) -> append(s, e) }
            }
            if (bySentence) flush()
        }
        flush()
        return chunks
    }

    private fun trimmed(text: String, start: Int, end: Int): TtsPiece? {
        var s = start
        var e = end
        while (s < e && text[s] <= ' ') s++
        while (e > s && text[e - 1] <= ' ') e--
        return if (s < e) TtsPiece(text.substring(s, e), s, e) else null
    }

    private fun segments(iterator: BreakIterator, text: String, from: Int, to: Int): List<Pair<Int, Int>> {
        iterator.setText(text.substring(from, to))
        val out = mutableListOf<Pair<Int, Int>>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out.add(from + start to from + end)
            start = end
            end = iterator.next()
        }
        return out
    }

    /** The last resort, for a run with no break opportunity in it at all. Steps back off a leading
     *  surrogate so a cut cannot land inside a character and speak as a replacement glyph. */
    private fun cut(text: String, from: Int, to: Int, maxLength: Int): List<TtsPiece> {
        val out = mutableListOf<TtsPiece>()
        var start = from
        while (start < to) {
            var end = minOf(start + maxLength, to)
            if (end < to && Character.isHighSurrogate(text[end - 1])) end--
            out.add(TtsPiece(text.substring(start, end), start, end))
            start = end
        }
        return out
    }
}
