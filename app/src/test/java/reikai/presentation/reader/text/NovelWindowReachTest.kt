package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelWindowReachTest {

    private val order = listOf(1L, 2L, 3L, 4L, 5L, 6L)
    private val after: (Long) -> Long? = { id -> order.getOrNull(order.indexOf(id) + 1) }

    @Test
    fun `a next chapter taller than the screen is the whole reach`() {
        NovelWindowReach.forward(next = 2L, after = after, fitsOnScreen = { false }) shouldBe listOf(2L)
    }

    @Test
    fun `a next chapter that fits pulls in the one after it`() {
        NovelWindowReach.forward(next = 2L, after = after, fitsOnScreen = { it == 2L }) shouldBe listOf(2L, 3L)
    }

    @Test
    fun `a run of chapters that fit is capped`() {
        NovelWindowReach.forward(next = 2L, after = after, fitsOnScreen = { true }) shouldBe listOf(2L, 3L, 4L)
    }

    @Test
    fun `the reach stops at the end of the novel`() {
        NovelWindowReach.forward(next = 6L, after = after, fitsOnScreen = { true }) shouldBe listOf(6L)
    }

    @Test
    fun `no next chapter reaches nothing`() {
        NovelWindowReach.forward(next = null, after = after, fitsOnScreen = { true }) shouldBe emptyList()
    }
}
