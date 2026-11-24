package reikai.presentation.reader.settings

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.DualPageView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Settings > Reader offers no row the running renderer ignores. */
class MangaReaderSettingsRowsTest {

    /** Settings has no screen to ask, so only Always is known to show two pages. */
    @ParameterizedTest(name = "can spread {0}, {1}")
    @CsvSource("true, ALWAYS, false", "true, WIDE, true", "true, NEVER, true", "false, ALWAYS, true")
    fun `Settings hides the single page rows only where two pages surely show`(
        canSpread: Boolean,
        dualPageView: DualPageView,
        shown: Boolean,
    ) {
        singlePageRowsShown(canSpread, dualPageView) shouldBe shown
    }

    /** When wide counts here, since it may show two pages. */
    @ParameterizedTest(name = "can spread {0}, {1}")
    @CsvSource("true, ALWAYS, true", "true, WIDE, true", "true, NEVER, false", "false, ALWAYS, false")
    fun `Settings offers the spread rows wherever two pages may show`(
        canSpread: Boolean,
        dualPageView: DualPageView,
        shown: Boolean,
    ) {
        dualPageRowsShown(canSpread, dualPageView) shouldBe shown
    }

    @Test
    fun `manga's rows ask the rule for the high quality renderer`() {
        val rows = MangaReaderSettingsRows(highQualityRenderer = true, DualPageView.ALWAYS)

        rows.singlePageRows shouldBe false
        rows.dualPageRows shouldBe true
    }

    /** The screen half is showsTwoPages, tested where it lives; this pins the paged gate in front of it. */
    @ParameterizedTest(name = "paged {0}")
    @CsvSource("true, true", "false, false")
    fun `a reader sheet offers the spread rows only for paged reading`(paged: Boolean, shown: Boolean) {
        sheetDualPageRowsShown(paged, DualPageView.ALWAYS, 400, 800) shouldBe shown
    }

    @ParameterizedTest(name = "renderer {0}, crop borders {1}, {2}")
    @CsvSource(
        "true, true, NEVER, true",
        "true, false, NEVER, false",
        "false, true, NEVER, false",
        "true, true, ALWAYS, false",
    )
    fun `crop instead of zoom shows with the renderer and crop borders on, outside a spread`(
        highQuality: Boolean,
        cropBorders: Boolean,
        dualPageView: DualPageView,
        shown: Boolean,
    ) {
        MangaReaderSettingsRows(highQuality, dualPageView).cropInsteadOfZoom(cropBorders) shouldBe shown
    }
}
