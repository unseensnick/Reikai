package reikai.presentation.reader

import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class StripCaptureSizeTest {

    @Test
    fun `a full reach of chrome pages fits the heap on a large window`() {
        val size = stripCaptureSize(2256, 2504, heightFraction = 1f)
        val mostPagesInReach = ImageViewerContinuousState.MAX_VISIBLE_PAGES + 3

        (size.width * size.height * 4L * mostPagesInReach < 200_000_000L) shouldBe true
    }

    @Test
    fun `a page keeps the viewer's shape, so its slot does not change`() {
        val size = stripCaptureSize(2560, 1600, heightFraction = 1f)

        size.width * 1600 shouldBe size.height * 2560
    }

    @Test
    fun `an error page keeps the shorter slot the strip gave it`() {
        val size = stripCaptureSize(2560, 1600, heightFraction = 0.8f)

        size.height * 10 shouldBe 1600 * 8 / STRIP_CAPTURE_SCALE
    }

    @Test
    fun `a capture is smaller than the viewer`() {
        val size = stripCaptureSize(2560, 1600, heightFraction = 1f)

        size.width shouldNotBe 2560
    }

    @Test
    fun `a window too small to divide still gets a page`() {
        val size = stripCaptureSize(1, 1, heightFraction = 1f)

        (size.width to size.height) shouldBe (1 to 1)
    }
}
