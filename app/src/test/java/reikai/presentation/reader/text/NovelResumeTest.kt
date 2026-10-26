package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterProgress

class NovelResumeTest {

    /** To the hundredth, so a paged chapter reopens on the page it was left on, not one a rounding moved. */
    @Test
    fun `an unread chapter opens where it was left`() {
        NovelResume.progress(read = false, lastTextProgress = 4_217L, preserveOnRead = false) shouldBe
            ChapterProgress.Percent(4_217L)
    }

    @Test
    fun `a read chapter opens at its start`() {
        NovelResume.progress(read = true, lastTextProgress = 10_000L, preserveOnRead = false) shouldBe
            ChapterProgress.Percent(0)
    }

    @Test
    fun `a stored position past the end opens at the end`() {
        NovelResume.progress(read = false, lastTextProgress = 12_000L, preserveOnRead = false) shouldBe
            ChapterProgress.Percent(10_000L)
    }

    @Test
    fun `a read chapter opens where it was left when positions are kept`() {
        NovelResume.progress(read = true, lastTextProgress = 4_200L, preserveOnRead = true) shouldBe
            ChapterProgress.Percent(4_200L)
    }
}
