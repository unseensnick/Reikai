package reikai.domain.readinglog

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingPaceTest {

    private fun page(entryId: Long, secondsPerPage: Double, pages: Long = 20) =
        PaceSample(entryId, (secondsPerPage * 1000 * pages).toLong(), pages)

    @Test
    fun `fewer than three finished chapters is still learning`() {
        learnPace(listOf(page(1, 5.0), page(2, 6.0)), seriesIds = setOf(1), PaceUnit.PAGE) shouldBe ReadingPace.Learning
    }

    @Test
    fun `a series without three chapters of its own takes the overall pace`() {
        val samples = listOf(page(2, 4.0), page(3, 6.0), page(4, 8.0))

        learnPace(samples, seriesIds = setOf(1), PaceUnit.PAGE) shouldBe ReadingPace.Known(6_000.0)
    }

    @Test
    fun `a series with three chapters of its own takes its own pace`() {
        val samples = listOf(page(1, 10.0), page(2, 3.0), page(1, 12.0), page(2, 3.0), page(1, 14.0))

        learnPace(samples, seriesIds = setOf(1), PaceUnit.PAGE) shouldBe ReadingPace.Known(12_000.0)
    }

    @Test
    fun `a merged series learns from every member's chapters`() {
        val samples = listOf(page(1, 10.0), page(2, 3.0), page(5, 12.0), page(2, 3.0), page(6, 14.0))

        learnPace(samples, seriesIds = setOf(1, 5, 6), PaceUnit.PAGE) shouldBe ReadingPace.Known(12_000.0)
    }

    @Test
    fun `no series asks for the overall pace`() {
        val samples = listOf(page(1, 10.0), page(1, 12.0), page(1, 14.0), page(2, 2.0), page(2, 3.0))

        learnPace(samples, seriesIds = null, PaceUnit.PAGE) shouldBe ReadingPace.Known(10_000.0)
    }

    @Test
    fun `a chapter skimmed through or left open does not count`() {
        // Under a second a page is tapping through; over two minutes a page is the reader left open.
        val samples = listOf(page(1, 0.2), page(1, 300.0), page(1, 5.0), page(1, 7.0))

        learnPace(samples, seriesIds = setOf(1), PaceUnit.PAGE) shouldBe ReadingPace.Learning
    }

    @Test
    fun `only the most recent twenty chapters count`() {
        val recent = List(20) { page(1, 4.0) }
        val older = List(30) { page(1, 9.0) }

        learnPace(recent + older, seriesIds = setOf(1), PaceUnit.PAGE) shouldBe ReadingPace.Known(4_000.0)
    }

    @Test
    fun `an even count takes the middle two`() {
        val samples = listOf(page(1, 4.0), page(1, 5.0), page(1, 7.0), page(1, 9.0))

        learnPace(samples, seriesIds = setOf(1), PaceUnit.PAGE) shouldBe ReadingPace.Known(6_000.0)
    }

    @Test
    fun `words are bounded as words`() {
        // 240 words a minute is 250 ms a word; a page's floor would throw every one of them out.
        val samples = List(3) { PaceSample(1, 250L * 2_000, 2_000) }

        learnPace(samples, seriesIds = setOf(1), PaceUnit.WORD) shouldBe ReadingPace.Known(250.0)
    }
}
