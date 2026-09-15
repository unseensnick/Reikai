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

    /** Neither viewport can put a chapter between two it already shows, so one arriving late would be skipped. */
    @Test
    fun `a chapter after one not yet loaded stays out of the window`() {
        NovelWindowReach.windowIds(previous = null, current = 1L, forward = listOf(2L, 3L), isCached = {
            it == 3L
        }) shouldBe
            listOf(1L)
    }

    @Test
    fun `the window holds every loaded chapter of the reach around the current one`() {
        NovelWindowReach.windowIds(previous = 0L, current = 1L, forward = listOf(2L, 3L), isCached = { true }) shouldBe
            listOf(0L, 1L, 2L, 3L)
    }

    @Test
    fun `the reach stops at the end of the novel`() {
        NovelWindowReach.forward(next = 6L, after = after, fitsOnScreen = { true }) shouldBe listOf(6L)
    }

    @Test
    fun `no next chapter reaches nothing`() {
        NovelWindowReach.forward(next = null, after = after, fitsOnScreen = { true }) shouldBe emptyList()
    }

    @Test
    fun `the chapter before waits while the next one is still arriving`() {
        NovelWindowReach.previousMayJoin(forward = listOf(3L), resolved = { false }, alreadyHeld = false) shouldBe false
    }

    @Test
    fun `the chapter before joins once the next one has resolved`() {
        NovelWindowReach.previousMayJoin(forward = listOf(3L), resolved = { true }, alreadyHeld = false) shouldBe true
    }

    @Test
    fun `the chapter before waits for every chapter of the reach`() {
        val onlyTheNext: (Long) -> Boolean = { it == 3L }
        NovelWindowReach.previousMayJoin(listOf(3L, 4L), onlyTheNext, alreadyHeld = false) shouldBe false
    }

    @Test
    fun `the chapter before joins at once at the end of the novel`() {
        NovelWindowReach.previousMayJoin(forward = emptyList(), resolved = { false }, alreadyHeld = false) shouldBe true
    }

    @Test
    fun `a chapter before that the window already holds stays`() {
        NovelWindowReach.previousMayJoin(forward = listOf(3L), resolved = { false }, alreadyHeld = true) shouldBe true
    }
}
