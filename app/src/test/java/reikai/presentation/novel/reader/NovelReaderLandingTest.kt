package reikai.presentation.novel.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelReaderLandingTest {

    @Test
    fun `a chapter lands on its own resume position when it opens`() {
        val landing = NovelReaderLanding()

        landing.opened(chapterId = 1L, resumePercent = 30)

        landing.percent shouldBe 30
    }

    @Test
    fun `a rebuilt page lands where the reader got to, not where the chapter opened`() {
        val landing = NovelReaderLanding()
        landing.opened(chapterId = 1L, resumePercent = 30)
        landing.reported(60)

        landing.opened(chapterId = 1L, resumePercent = 30)

        landing.percent shouldBe 60
    }

    @Test
    fun `a different chapter drops the position reached in the one before it`() {
        val landing = NovelReaderLanding()
        landing.opened(chapterId = 1L, resumePercent = 30)
        landing.reported(60)

        landing.opened(chapterId = 2L, resumePercent = 0)

        landing.percent shouldBe 0
    }

    @Test
    fun `a reported position outside 0 to 100 is clamped`() {
        val landing = NovelReaderLanding()

        landing.reported(140)

        landing.percent shouldBe 100
    }
}
