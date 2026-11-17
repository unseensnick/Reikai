package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.readinglog.PaceUnit

class LiveReadingTest {

    private fun LiveReading.page(page: Int, atMs: Long, chapterId: Long = 1) =
        next(chapterId, units = 30, ChapterProgress.Pages(page, pageCount = 30), atMs, PaceUnit.PAGE)

    @Test
    fun `three pages read give a pace`() {
        LiveReading().page(0, atMs = 0).page(3, atMs = 30_000).msPerUnit shouldBe 10_000.0
    }

    @Test
    fun `two pages are not enough`() {
        LiveReading().page(0, atMs = 0).page(2, atMs = 30_000).msPerUnit shouldBe null
    }

    @Test
    fun `a few seconds are not enough`() {
        LiveReading().page(0, atMs = 0).page(3, atMs = 12_000).msPerUnit shouldBe null
    }

    @Test
    fun `a jump ahead starts the measure again from where it landed`() {
        val jumped = LiveReading().page(0, atMs = 0).page(15, atMs = 5_000)

        jumped.page(18, atMs = 35_000).msPerUnit shouldBe 10_000.0
    }

    @Test
    fun `a scroll's quick small steps are reading, not a jump`() {
        // A flick reports each percent of a 2,500-word chapter a fifth of a second apart.
        fun LiveReading.at(hundredths: Long, atMs: Long) =
            next(1, units = 2_500, ChapterProgress.Percent(hundredths), atMs, PaceUnit.WORD)

        val scrolled = LiveReading().at(0, 0).at(100, 9_000).at(200, 9_200).at(300, 9_400).at(400, 9_600)

        scrolled.at(800, 30_000).msPerUnit shouldBe 150.0
    }

    @Test
    fun `pages flipped faster than anyone reads start the measure again`() {
        val flipped = LiveReading().page(0, atMs = 0).page(1, atMs = 200).page(2, atMs = 400)
            .page(3, atMs = 600).page(4, atMs = 800).page(5, atMs = 1_000)

        flipped.page(8, atMs = 31_000).msPerUnit shouldBe 10_000.0
    }

    @Test
    fun `a jump after a long read starts the measure again`() {
        val read = LiveReading().page(0, atMs = 0).page(3, atMs = 30_000).page(18, atMs = 35_000)

        read.page(21, atMs = 95_000).msPerUnit shouldBe 20_000.0
    }

    @Test
    fun `going back keeps the pace`() {
        val measured = LiveReading().page(0, atMs = 0).page(3, atMs = 30_000)

        measured.page(1, atMs = 40_000).msPerUnit shouldBe 10_000.0
    }

    @Test
    fun `going back measures again from there`() {
        val back = LiveReading().page(0, atMs = 0).page(3, atMs = 30_000).page(1, atMs = 40_000)

        back.page(4, atMs = 100_000).msPerUnit shouldBe 20_000.0
    }

    @Test
    fun `a reader left open on a page measures nothing`() {
        LiveReading().page(0, atMs = 0).page(3, atMs = 60L * 60 * 1000).msPerUnit shouldBe null
    }

    @Test
    fun `a new chapter keeps the pace already measured`() {
        val measured = LiveReading().page(0, atMs = 0).page(3, atMs = 30_000)

        measured.page(0, atMs = 31_000, chapterId = 2).msPerUnit shouldBe 10_000.0
    }

    @Test
    fun `a new chapter measures from its own first page`() {
        val next = LiveReading().page(0, atMs = 0).page(3, atMs = 30_000).page(0, atMs = 31_000, chapterId = 2)

        next.page(3, atMs = 91_000, chapterId = 2).msPerUnit shouldBe 20_000.0
    }

    @Test
    fun `the position measured from is the one the time left counts from`() {
        LiveReading().page(0, atMs = 0).page(3, atMs = 30_000).progress shouldBe ChapterProgress.Pages(3, 30)
    }

    @Test
    fun `no position keeps the pace and measures again once one returns`() {
        val lost = LiveReading().page(0, atMs = 0).page(3, atMs = 30_000)
            .next(1, units = 30, progress = null, nowMs = 31_000, PaceUnit.PAGE)

        (lost.progress to lost.msPerUnit) shouldBe (null to 10_000.0)
    }

    @Test
    fun `a novel's pace is counted in words`() {
        // 10% of 2,000 words is 200 words, read in 50 seconds.
        val novel = LiveReading()
            .next(1, units = 2_000, ChapterProgress.Percent(0), nowMs = 0, PaceUnit.WORD)
            .next(1, units = 2_000, ChapterProgress.Percent(1_000), nowMs = 50_000, PaceUnit.WORD)

        novel.msPerUnit shouldBe 250.0
    }
}
