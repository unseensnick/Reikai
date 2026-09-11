package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelResumeTest {

    @Test
    fun `an unread chapter opens where it was left`() {
        NovelResume.percent(read = false, lastTextProgress = 4_200L) shouldBe 42
    }

    @Test
    fun `a read chapter opens at its start`() {
        NovelResume.percent(read = true, lastTextProgress = 10_000L) shouldBe 0
    }

    @Test
    fun `a stored position past the end opens at the end`() {
        NovelResume.percent(read = false, lastTextProgress = 12_000L) shouldBe 100
    }

    @Test
    fun `a rebuilt renderer at a chapter's end lands on the start of the chapter held below it`() {
        NovelResume.relanding(chapterId = 1L, livePercent = 100, nextId = 2L) shouldBe (2L to 0)
    }

    @Test
    fun `a rebuilt renderer at the end of the last chapter stays on it`() {
        NovelResume.relanding(chapterId = 1L, livePercent = 100, nextId = null) shouldBe (1L to 100)
    }

    @Test
    fun `a rebuilt renderer part way through a chapter goes back to that position`() {
        NovelResume.relanding(chapterId = 1L, livePercent = 40, nextId = 2L) shouldBe (1L to 40)
    }
}
