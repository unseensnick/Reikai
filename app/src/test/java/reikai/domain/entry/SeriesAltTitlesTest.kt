package reikai.domain.entry

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The one rule for a series' other titles. */
class SeriesAltTitlesTest {

    @Test
    fun `member titles come first, then stored, without the main title`() {
        seriesAltTitles("Main", listOf("Main", "Sibling"), listOf("Stored")) shouldBe listOf("Sibling", "Stored")
    }

    @Test
    fun `repeats are dropped ignoring case and space, the first spelling wins`() {
        seriesAltTitles("Main", listOf(" Demon Slayer "), listOf("demon slayer", " MAIN")) shouldBe
            listOf("Demon Slayer")
    }

    @Test
    fun `blank titles are dropped`() {
        seriesAltTitles("Main", listOf("", "  "), listOf("A")) shouldBe listOf("A")
    }

    @Test
    fun `there is no cap`() {
        seriesAltTitles("M", emptyList(), listOf("A", "B", "C", "D")).size shouldBe 4
    }
}
