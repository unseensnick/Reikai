package reikai.presentation.reader

import android.view.KeyEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelVolumeKeysTest {

    @Test
    fun `a fraction below the minimum moves a tenth of the screen`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted = false, fraction = 0.05f) shouldBe 0.1f
    }

    @Test
    fun `a fraction above a screen moves one screen`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted = false, fraction = 1.5f) shouldBe 1f
    }

    @Test
    fun `volume down reads on`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted = false, fraction = 0.5f) shouldBe 0.5f
    }

    @Test
    fun `volume up reads back`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_UP, inverted = false, fraction = 0.5f) shouldBe -0.5f
    }

    @Test
    fun `inverted volume down reads back`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted = true, fraction = 0.5f) shouldBe -0.5f
    }

    @Test
    fun `page down is not a volume key`() {
        NovelVolumeKeys.isVolumeKey(KeyEvent.KEYCODE_PAGE_DOWN) shouldBe false
    }
}
