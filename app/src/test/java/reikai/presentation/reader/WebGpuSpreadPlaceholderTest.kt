package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WebGpuSpreadPlaceholderTest {

    /** A square page on a 2000x1200 screen: half the screen is 1000 wide, the page's shape is 1200. */
    @Test
    fun `a placeholder beside a decoded page takes that page's shape`() {
        placeholderSideWidth(
            isSpreadSide = true,
            partnerAspect = 1f,
            viewportWidth = 1000,
            viewportHeight = 1200,
        ) shouldBe 1200
    }

    @Test
    fun `a placeholder beside a narrow page is as narrow`() {
        placeholderSideWidth(
            isSpreadSide = true,
            partnerAspect = 0.5f,
            viewportWidth = 1000,
            viewportHeight = 1200,
        ) shouldBe 600
    }

    @Test
    fun `two placeholders share the screen in halves`() {
        placeholderSideWidth(
            isSpreadSide = true,
            partnerAspect = null,
            viewportWidth = 1000,
            viewportHeight = 1200,
        ) shouldBe 1000
    }

    /** A partner it was last drawn beside says nothing once the page fills the viewer alone. */
    @Test
    fun `a placeholder drawn alone fills the screen whatever it was last beside`() {
        placeholderSideWidth(
            isSpreadSide = false,
            partnerAspect = 1f,
            viewportWidth = 2000,
            viewportHeight = 1200,
        ) shouldBe 2000
    }

    /** A page not measured yet has no shape to take. */
    @Test
    fun `a partner with no shape leaves the placeholder its half`() {
        placeholderSideWidth(
            isSpreadSide = true,
            partnerAspect = 0f,
            viewportWidth = 1000,
            viewportHeight = 1200,
        ) shouldBe 1000
    }
}
