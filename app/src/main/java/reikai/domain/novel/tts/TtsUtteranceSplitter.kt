package reikai.domain.novel.tts

import reikai.domain.novel.text.TextSegments
import java.util.Locale

/** A piece of a paragraph to speak, as [TtsSpeechText] gives it, and where it sits in the paragraph as shown:
 *  [start] until [end]. */
data class TtsPiece(val text: String, val start: Int, val end: Int)

/**
 * Breaks a paragraph into pieces a speech engine will speak. An utterance past the engine's maximum
 * fails, often later through `onError` rather than at `speak`, so a long paragraph goes out as several.
 *
 * Sentences first, then [TextSegments.lines] for one longer than the cap, which segments Chinese,
 * Japanese, Thai and Khmer by dictionary. The cap is never exceeded, whatever the text.
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
        // Cut from the speech text at offsets found in the text as shown, where sentences end.
        val speech = TtsSpeechText.of(text)
        if (!bySentence &&
            trail - lead <= maxLength
        ) {
            return listOf(TtsPiece(speech.substring(lead, trail), lead, trail)).spoken()
        }

        val chunks = mutableListOf<TtsPiece>()
        // The chunk being packed, as a span of text: segments are contiguous, so a start and an end hold it.
        var packedStart = -1
        var packedEnd = -1

        fun flush() {
            if (packedStart >= 0) trimmed(text, speech, packedStart, packedEnd)?.let(chunks::add)
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
                chunks.addAll(cut(text, speech, start, end, maxLength))
            }
        }

        for ((start, end) in TextSegments.sentences(text, lead, trail, locale)) {
            if (end - start <= maxLength) {
                append(start, end)
            } else {
                // Only now, because a sentence that fits should stay whole even where it could break.
                flush()
                TextSegments.lines(text, start, end, locale).forEach { (s, e) -> append(s, e) }
            }
            if (bySentence) flush()
        }
        flush()
        return chunks.spoken()
    }

    /**
     * Without a piece that has no letter or digit left, such as a scene break (`***`, `◇◆◇`) or a lone
     * `……`: a voice reads it out by name, or for blank text may never report it done. A paragraph left
     * with none is skipped, since the engine finishes an empty list at once.
     */
    private fun List<TtsPiece>.spoken() =
        filter { piece -> piece.text.codePoints().anyMatch(Character::isLetterOrDigit) }

    private fun trimmed(text: String, speech: String, start: Int, end: Int): TtsPiece? {
        var s = start
        var e = end
        while (s < e && text[s] <= ' ') s++
        while (e > s && text[e - 1] <= ' ') e--
        return if (s < e) TtsPiece(speech.substring(s, e), s, e) else null
    }

    /** The last resort, for a run with no break opportunity in it at all. Steps back off a leading
     *  surrogate so a cut cannot land inside a character and speak as a replacement glyph. */
    private fun cut(text: String, speech: String, from: Int, to: Int, maxLength: Int): List<TtsPiece> {
        val out = mutableListOf<TtsPiece>()
        var start = from
        while (start < to) {
            var end = minOf(start + maxLength, to)
            if (end < to && Character.isHighSurrogate(text[end - 1])) end--
            out.add(TtsPiece(speech.substring(start, end), start, end))
            start = end
        }
        return out
    }
}
