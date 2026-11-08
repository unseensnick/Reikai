package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The page a long press on the WebGPU viewers acts on: the one under the finger, not the current one. */
class WebGpuPressedPageTest {

    private val heights = listOf(1000f, 500f, 2000f)

    private fun strip(pressY: Float, scale: Float = 1f, scrollY: Float = 0f, gap: Float = 0f) =
        stripPageAt(pressY, viewerHeight = 1000f, scale, scrollY, gap, maxPages = 24) { heights.getOrNull(it) }

    @Test
    fun `a press on an unzoomed spread's right half is the right page`() {
        pressIsLeftOfSeam(pressX = 0.7f, x = 0f, scale = 1f, rendererOffsetX = 0f) shouldBe false
    }

    @Test
    fun `a press on an unzoomed spread's left half is the left page`() {
        pressIsLeftOfSeam(pressX = 0.3f, x = 0f, scale = 1f, rendererOffsetX = 0f) shouldBe true
    }

    @Test
    fun `a spread panned left moves its seam with it`() {
        // Zoomed to 2x and panned by -0.2, the seam sits at 0.5 + 2 * -0.2 = 0.1 of the width.
        pressIsLeftOfSeam(pressX = 0.3f, x = -0.2f, scale = 2f, rendererOffsetX = 0f) shouldBe false
    }

    @Test
    fun `the top page takes a press near the top of an unscrolled strip`() {
        strip(pressY = 0.5f) shouldBe 0
    }

    @Test
    fun `a press past the top page lands on the one below it`() {
        strip(pressY = 0.8f, scrollY = 600f) shouldBe 1
    }

    @Test
    fun `zooming out shows more pages, so the same press lands further down`() {
        strip(pressY = 0.9f, scale = 0.5f) shouldBe 2
    }

    @Test
    fun `a press in the gap between pages lands on none`() {
        strip(pressY = 0.05f, scrollY = 1000f, gap = 100f) shouldBe null
    }

    @Test
    fun `a press past the last measured page lands on none`() {
        strip(pressY = 0.9f, scale = 0.25f) shouldBe null
    }

    @Test
    fun `a press above the top page lands on none`() {
        strip(pressY = 0.1f, scrollY = -200f) shouldBe null
    }
}
