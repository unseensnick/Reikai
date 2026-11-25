package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What one stored spread-gap step draws, which the manga and novel spreads both read through this. */
class SpreadGapFractionTest {

    @Test
    fun `the widest stored gap is a fifth of a page`() {
        ReaderRanges.spreadGapFraction(ReaderRanges.spreadGapPercent.last) shouldBe 0.2f
    }
}
