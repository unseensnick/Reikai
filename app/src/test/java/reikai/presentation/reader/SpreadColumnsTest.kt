package reikai.presentation.reader

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Where a two-page novel spread puts each page's text, with the spread gap between them. */
class SpreadColumnsTest {

    private val innerMargin = 42

    private fun columns(gapFraction: Float, outerLeft: Int = 40, outerRight: Int = 40) =
        SpreadColumns.of(w = 2000, outerLeft, outerRight, innerMargin, gapFraction)

    /** From the left page's last text pixel to the right page's first. */
    private fun SpreadColumns.textGap() = (rightX + innerMargin) - (leftX + width - innerMargin)

    @Test
    fun `with no gap the two pages' text is both inner margins apart`() {
        columns(gapFraction = 0f).textGap() shouldBe 2 * innerMargin
    }

    @Test
    fun `the gap adds its share of a page's text width between the two pages' text`() {
        val columns = columns(gapFraction = 0.1f)
        val textWidth = columns.width - 2 * innerMargin
        (columns.textGap() - 2 * innerMargin).toDouble() shouldBe (0.1 * textWidth plusOrMinus 1.0)
    }

    @Test
    fun `the left page's text starts at the reader's left margin`() {
        columns(gapFraction = 0.1f).let { it.leftX + innerMargin } shouldBe 40
    }

    @Test
    fun `the right page's text ends at the reader's right margin`() {
        columns(gapFraction = 0.1f, outerRight = 64).let { it.rightX + it.width - innerMargin } shouldBe 2000 - 64
    }

    @Test
    fun `with no gap the two columns meet in the middle`() {
        columns(gapFraction = 0f).let { it.leftX + it.width } shouldBe 1000
    }
}
