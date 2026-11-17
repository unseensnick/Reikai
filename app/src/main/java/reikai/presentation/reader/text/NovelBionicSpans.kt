package reikai.presentation.reader.text

import android.graphics.Typeface
import android.text.Spannable
import android.text.style.StyleSpan
import reikai.novel.content.forEachMatch

/**
 * Bionic reading: bold the opening of each word so the eye can skim on the emphasised stems.
 *
 * The word-length-to-bold-length table is `text-vide`'s. This file is the reference, and the WebView
 * mode's reader.js mirrors it, so both modes emphasise the same letters. Its shape is a list of length boundaries:
 * a word bolds its length minus the index of the first boundary it fits in, which grows the bold
 * run as words get longer rather than taking a flat fraction.
 */
object NovelBionicSpans {

    private val fixationBoundaries = intArrayOf(0, 4, 12, 17, 24, 29, 35, 42, 48)

    // text-vide's word is a run of letters and digits holding at least one letter. The run is matched and
    // then checked, since a pattern that must find the letter inside it backtracks over a long run of digits.
    private val alphanumericRun = Regex("""[\p{L}\p{Nd}]+""")

    fun apply(text: Spannable) {
        words(text).forEach { word ->
            val bold = boldLengthFor(word.last - word.first + 1)
            if (bold <= 0) return@forEach
            text.setSpan(StyleSpan(Typeface.BOLD), word.first, word.first + bold, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    internal fun words(text: CharSequence): List<IntRange> = buildList {
        alphanumericRun.forEachMatch(text) { start, end ->
            if (hasLetter(text, start, end)) add(start until end)
        }
    }

    // By code point, as `\p{L}` reads, and in place: a chapter's subSequence copies every span over the run.
    private fun hasLetter(text: CharSequence, start: Int, end: Int): Boolean {
        var at = start
        while (at < end) {
            val point = Character.codePointAt(text, at)
            if (Character.isLetter(point)) return true
            at += Character.charCount(point)
        }
        return false
    }

    fun boldLengthFor(wordLength: Int): Int {
        val index = fixationBoundaries.indexOfFirst { wordLength <= it }
        val bold = if (index == -1) wordLength - fixationBoundaries.size else wordLength - index
        return bold.coerceAtLeast(0)
    }
}
