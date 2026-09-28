package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScrollCarryTest {

    private val scrolled = mutableListOf<Int>()

    private fun ScrollCarry.frame(atMillis: Long, pxPerSecond: Float, accepts: Boolean = true) =
        onFrame(atMillis * 1_000_000L, pxPerSecond) { px ->
            scrolled += px
            accepts
        }

    /** 30px/s over 16ms frames is under half a pixel a frame, which rounds to nothing without the carry. */
    @Test
    fun `a speed under a pixel a frame still moves over several frames`() {
        val carry = ScrollCarry()
        carry.frame(1000, 30f)
        carry.frame(1016, 30f)
        carry.frame(1032, 30f)

        carry.frame(1048, 30f)

        scrolled shouldBe listOf(1)
    }

    @Test
    fun `the first frame only takes a timestamp`() {
        val carry = ScrollCarry()

        carry.frame(5000, 6000f)

        scrolled shouldBe emptyList()
    }

    /** 120px/s over 16ms frames is 1.92px a frame: without the drop the 0.92 kept back surfaces next. */
    @Test
    fun `a refused step drops the carry`() {
        val carry = ScrollCarry()
        carry.frame(1000, 120f)
        carry.frame(1016, 120f, accepts = false)
        scrolled.clear()

        carry.frame(1032, 120f)

        scrolled shouldBe listOf(1)
    }
}
