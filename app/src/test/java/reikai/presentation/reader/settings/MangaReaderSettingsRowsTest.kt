package reikai.presentation.reader.settings

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.DualPageView
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

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

    @Test
    fun `the renderer offers smart fit`() {
        ReaderPreferences.ImageScaleTypeWebGpuViewer shouldContain MR.strings.scale_type_smart_fit
    }

    @Test
    fun `the renderer offers stretch`() {
        ReaderPreferences.ImageScaleTypeWebGpuViewer shouldContain MR.strings.scale_type_stretch
    }

    @Test
    fun `a scale type keeps its stored value whichever list offers it`() {
        scaleTypeValues(ReaderPreferences.ImageScaleTypeWebGpuViewer).keys shouldBe setOf(1, 2, 3, 4, 5, 6)
    }
}
