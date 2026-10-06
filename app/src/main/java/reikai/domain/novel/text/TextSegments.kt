package reikai.domain.novel.text

import java.text.BreakIterator
import java.util.Locale

/**
 * Sentence and line segmentation, on [BreakIterator] rather than a punctuation list: its sentence
 * instance keeps a closing quote or bracket with its sentence and ends a Chinese or Japanese sentence
 * at its own full stop, and its line instance segments unspaced text by dictionary. Each segment is a
 * pair of offsets in the text it was cut from, and carries the whitespace that follows it.
 */
object TextSegments {

    fun sentences(text: String, from: Int, to: Int, locale: Locale): List<Pair<Int, Int>> =
        withOpenersMovedOn(text, segments(BreakIterator.getSentenceInstance(locale), text, from, to))

    fun lines(text: String, from: Int, to: Int, locale: Locale): List<Pair<Int, Int>> =
        segments(BreakIterator.getLineInstance(locale), text, from, to)

    /**
     * Android's sentence rule (ICU) counts an opening bracket or quote straight after a sentence's end as
     * part of that sentence, so in 「…。」「… the first ends after the second's bracket; the JDK's does not.
     * Each sentence but the last hands such an opener on to the next: an open bracket, or an opening
     * quote right after a closing one (”“), since German closes with that same quote character.
     */
    internal fun withOpenersMovedOn(text: String, segments: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val ends = segments.mapIndexed { index, (start, end) ->
            var moved = end
            while (index < segments.lastIndex && moved > start + 1 && opensNext(text, moved)) moved--
            moved
        }
        return ends.mapIndexed { index, end -> (if (index == 0) segments[0].first else ends[index - 1]) to end }
    }

    /** Whether the character before [end] opens what follows; [end] leaves at least two characters. */
    private fun opensNext(text: String, end: Int): Boolean = when (text[end - 1].category) {
        CharCategory.START_PUNCTUATION -> true
        CharCategory.INITIAL_QUOTE_PUNCTUATION -> text[end - 2].category in closers
        else -> false
    }

    private val closers = setOf(CharCategory.FINAL_QUOTE_PUNCTUATION, CharCategory.END_PUNCTUATION)

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
}
