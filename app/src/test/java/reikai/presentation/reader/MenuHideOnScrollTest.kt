package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Which scroll frames hide the menu, the one rule every reader's scrolling renderer feeds. */
class MenuHideOnScrollTest {

    private var now = 0L
    private val hide = MenuHideOnScroll(THRESHOLD) { now }

    private fun frame(dy: Float, touching: Boolean, atMs: Long): Boolean {
        now = atMs
        return hide.hides(dy, touching)
    }

    @Test
    fun `a drag past the threshold hides the menu`() {
        frame(dy = 20f, touching = true, atMs = 0) shouldBe true
    }

    @Test
    fun `a drag under the threshold leaves it`() {
        frame(dy = 5f, touching = true, atMs = 0) shouldBe false
    }

    @Test
    fun `the fling a drag lets go into hides the menu`() {
        frame(dy = 5f, touching = true, atMs = 0)

        frame(dy = 40f, touching = false, atMs = 16) shouldBe true
    }

    /** A fling runs for many frames after the finger lifts, and each one still counts. */
    @Test
    fun `a fling's later frames hide the menu`() {
        frame(dy = 5f, touching = true, atMs = 0)
        frame(dy = 5f, touching = false, atMs = 16)

        frame(dy = 40f, touching = false, atMs = 32) shouldBe true
    }

    /** Auto-scroll, a jump, a landing: nothing touched the screen. */
    @Test
    fun `a scroll the app makes leaves the menu`() {
        frame(dy = 40f, touching = false, atMs = 0) shouldBe false
    }

    /** The fling stopped, and the next scroll is the app's, though the renderer reported no stop. */
    @Test
    fun `a scroll after the fling has stopped leaves the menu`() {
        frame(dy = 5f, touching = true, atMs = 0)
        frame(dy = 5f, touching = false, atMs = 16)

        frame(dy = 40f, touching = false, atMs = 16 + MenuHideOnScroll.FLING_GAP_MS + 1) shouldBe false
    }

    private companion object {
        const val THRESHOLD = 10
    }
}
