package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The high quality renderer's long strip hides its menu once a scroll since it showed passes the threshold. */
class StripMenuHideTest {

    @Test
    fun `scrolls that add up past the threshold hide the menu`() {
        val hide = StripMenuHide(threshold = 10)
        listOf(hide.onScroll(6f, menuVisible = true), hide.onScroll(6f, menuVisible = true)) shouldBe
            listOf(false, true)
    }

    @Test
    fun `a scroll within the threshold leaves the menu`() {
        StripMenuHide(threshold = 10).onScroll(10f, menuVisible = true) shouldBe false
    }

    @Test
    fun `scrolling back toward where the menu showed counts against the distance`() {
        val hide = StripMenuHide(threshold = 10)
        hide.onScroll(8f, menuVisible = true)
        hide.onScroll(-8f, menuVisible = true)

        hide.onScroll(8f, menuVisible = true) shouldBe false
    }

    @Test
    fun `a menu shown again counts from where it showed`() {
        val hide = StripMenuHide(threshold = 10)
        hide.onScroll(8f, menuVisible = true)
        hide.onScroll(50f, menuVisible = false)

        hide.onScroll(8f, menuVisible = true) shouldBe false
    }

    @Test
    fun `a hidden menu is not hidden again`() {
        StripMenuHide(threshold = 10).onScroll(50f, menuVisible = false) shouldBe false
    }

    @Test
    fun `the scroll that hid the menu is spent`() {
        val hide = StripMenuHide(threshold = 10)
        hide.onScroll(11f, menuVisible = true)

        hide.onScroll(1f, menuVisible = true) shouldBe false
    }
}
