package reikai.domain.novel

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

    /** A reader closing while read aloud goes on must not let read aloud finish the same chapter again. */
    @Test
    fun `a chapter stays finished while another holder remains`() {
        val reader = latch.hold()
        latch.hold()
        latch.claim(1L)

        reader.close()

        latch.claim(1L) shouldBe false
    }

    @Test
    fun `a chapter can be finished again once nothing holds the latch`() {
        val reader = latch.hold()
        latch.claim(1L)

        reader.close()

        latch.claim(1L) shouldBe true
    }

    @Test
    fun `closing a hold twice lets go of it once`() {
        val reader = latch.hold()
        latch.hold()
        latch.claim(1L)

        reader.close()
        reader.close()

        latch.claim(1L) shouldBe false
    }
}
