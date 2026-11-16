package reikai.presentation.reader.settings

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.DualPageView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Settings > Reader offers no row the running renderer ignores. */
class MangaReaderSettingsRowsTest {

    private fun rows(highQuality: Boolean, dualPageView: DualPageView = DualPageView.NEVER) =
        MangaReaderSettingsRows(highQuality, dualPageView)

    @Test
    fun `the renderer's own rows show only with it on`() {
        listOf(rows(true).rendererRows, rows(false).rendererRows) shouldBe listOf(true, false)
    }

    @Test
    fun `double tap animation speed hides with the renderer on`() {
        listOf(rows(true).doubleTapAnimSpeed, rows(false).doubleTapAnimSpeed) shouldBe listOf(false, true)
    }

    @Test
    fun `single page rows hide only when the renderer always shows two pages`() {
        listOf(
            rows(true, DualPageView.ALWAYS).singlePageRows,
            rows(true, DualPageView.WIDE).singlePageRows,
            rows(false, DualPageView.ALWAYS).singlePageRows,
        ) shouldBe listOf(false, true, true)
    }

    @Test
    fun `the dual page variants show while the renderer may show two pages`() {
        listOf(
            rows(true, DualPageView.WIDE).dualPageRows,
            rows(true, DualPageView.NEVER).dualPageRows,
            rows(false, DualPageView.ALWAYS).dualPageRows,
        ) shouldBe listOf(true, false, false)
    }

    @Test
    fun `a paged novel offers the spread rows while it may show two pages`() {
        listOf(
            dualPageRowsShown(canSpread = true, DualPageView.WIDE),
            dualPageRowsShown(canSpread = true, DualPageView.NEVER),
            dualPageRowsShown(canSpread = false, DualPageView.ALWAYS),
        ) shouldBe listOf(true, false, false)
    }

    @Test
    fun `a reader sheet offers the spread rows while paged reading shows two pages on this screen`() {
        listOf(
            sheetDualPageRowsShown(paged = true, DualPageView.ALWAYS, 400, 800),
            sheetDualPageRowsShown(paged = true, DualPageView.WIDE, 800, 400),
            sheetDualPageRowsShown(paged = true, DualPageView.WIDE, 400, 800),
            sheetDualPageRowsShown(paged = false, DualPageView.ALWAYS, 800, 400),
        ) shouldBe listOf(true, true, false, false)
    }

    @Test
    fun `crop instead of zoom shows with the renderer and crop borders on`() {
        rows(true).cropInsteadOfZoom(cropBorders = true) shouldBe true
    }

    @Test
    fun `crop instead of zoom needs crop borders`() {
        rows(true).cropInsteadOfZoom(cropBorders = false) shouldBe false
    }

    @Test
    fun `crop instead of zoom hides with the renderer off`() {
        rows(false).cropInsteadOfZoom(cropBorders = true) shouldBe false
    }

    @Test
    fun `crop instead of zoom hides while the renderer always shows two pages`() {
        rows(true, DualPageView.ALWAYS).cropInsteadOfZoom(cropBorders = true) shouldBe false
    }
}
