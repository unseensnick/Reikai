package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ScaleType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The home zoom the high quality renderer starts a page at, for the scale types it sets itself. */
class WebGpuFitScaleTest {

    // A 1000 x 2000 screen.
    private fun scale(type: ScaleType, width: Float, height: Float) = fitHomeScale(type, 1000f, 2000f, width, height)

    @Test
    fun `smart fit fills the width with a tall page`() {
        scale(ScaleType.SMART_FIT, 500f, 4000f) shouldBe 2f
    }

    @Test
    fun `smart fit fills the height with a wide page`() {
        scale(ScaleType.SMART_FIT, 4000f, 500f) shouldBe 4f
    }

    @Test
    fun `smart fit fills the height with a square page`() {
        scale(ScaleType.SMART_FIT, 500f, 500f) shouldBe 4f
    }

    @Test
    fun `fit width fills the width`() {
        scale(ScaleType.FIT_WIDTH, 4000f, 500f) shouldBe 0.25f
    }

    @Test
    fun `fit height fills the height`() {
        scale(ScaleType.FIT_HEIGHT, 500f, 4000f) shouldBe 0.5f
    }

    @Test
    fun `original size draws a pixel as a pixel`() {
        scale(ScaleType.ORIGINAL_SIZE, 500f, 4000f) shouldBe 1f
    }

    @Test
    fun `landscape zoom zooms a wide page under fit screen`() {
        landscapeZoomApplies(landscapeZoom = true, ScaleType.FIT_SCREEN) shouldBe true
    }

    @Test
    fun `landscape zoom leaves smart fit's own zoom`() {
        landscapeZoomApplies(landscapeZoom = true, ScaleType.SMART_FIT) shouldBe false
    }

    @Test
    fun `landscape zoom off zooms nothing`() {
        landscapeZoomApplies(landscapeZoom = false, ScaleType.FIT_SCREEN) shouldBe false
    }

    @Test
    fun `fit screen leaves the library's own fit`() {
        scale(ScaleType.FIT_SCREEN, 500f, 4000f) shouldBe null
    }

    @Test
    fun `stretch fills the screen with a tall page, cropping its height`() {
        scale(ScaleType.STRETCH, 500f, 4000f) shouldBe 2f
    }

    @Test
    fun `stretch fills the screen with a wide page, cropping its width`() {
        scale(ScaleType.STRETCH, 4000f, 500f) shouldBe 4f
    }
}
