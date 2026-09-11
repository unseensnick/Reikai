package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelCompletionLatchTest {

    private val latch = NovelCompletionLatch()

    @Test
    fun `a chapter is finished the first time it completes`() {
        latch.claim(1L) shouldBe true
    }

    @Test
    fun `a chapter already finished is not finished again`() {
        latch.claim(1L)
        latch.claim(1L) shouldBe false
    }

    @Test
    fun `an unmarked chapter can be finished again`() {
        latch.claim(1L)
        latch.release(listOf(1L))
        latch.claim(1L) shouldBe true
    }
}
