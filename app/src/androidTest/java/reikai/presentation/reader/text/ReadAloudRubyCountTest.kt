package reikai.presentation.reader.text

import android.text.SpannableStringBuilder
import android.text.Spanned
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A top line is counted without its ruby readings, and the counts are looked up per chunk rather than
 * rescanned. Checked against a count written out here, character by character, over readings that
 * overlap and that touch, which is where a merged coverage could drop or double a character.
 */
class ReadAloudRubyCountTest {

    private val chunk = SpannableStringBuilder("漢字かんじ 東京とうきょう text").apply {
        reading(2, 5)
        reading(3, 6)
        reading(8, 12)
        reading(12, 13)
    }

    private fun SpannableStringBuilder.reading(start: Int, end: Int) =
        setSpan(RubyReadingSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    private val covered = setOf(2, 3, 4, 5, 8, 9, 10, 11, 12)

    private fun expected(end: Int) = (0 until end).count { it !in covered && !chunk[it].isWhitespace() }

    @Test
    fun theCountLeavesOutEveryReadingCharacter() {
        assertEquals((0..chunk.length).map(::expected), (0..chunk.length).map { shownCharCount(chunk, it) })
    }

    @Test
    fun thePrefixMatchesTheCountAtEveryOffset() {
        assertEquals((0..chunk.length).map(::expected), shownCharPrefix(chunk).toList())
    }
}
