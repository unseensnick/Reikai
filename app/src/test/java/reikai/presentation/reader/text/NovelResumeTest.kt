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
}
