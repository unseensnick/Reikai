package reikai.presentation.reader.text

import android.graphics.Typeface
import android.text.Spannable
import android.text.style.StyleSpan

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

    /** Letters and digits containing at least one letter, matching text-vide's word rule. */
    private val word = Regex("""(\p{L}|\p{Nd})*\p{L}(\p{L}|\p{Nd})*""")

    fun apply(text: Spannable) {
        word.findAll(text).forEach { match ->
            val bold = boldLengthFor(match.value.length)
            if (bold <= 0) return@forEach
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                match.range.first,
                match.range.first + bold,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    fun boldLengthFor(wordLength: Int): Int {
        val index = fixationBoundaries.indexOfFirst { wordLength <= it }
        val bold = if (index == -1) wordLength - fixationBoundaries.size else wordLength - index
        return bold.coerceAtLeast(0)
    }
}
