package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.novel.NovelTapLayout

/**
 * The rule both novel renderers read a tap through.
 */
class NovelTapZonesTest {

    // An empty action is a tap left to the page, where Mihon's own lookup would have opened the menu.
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "disabled opens the menu wherever, DISABLED, NONE, 12, 0.1, 0.9, false, MENU",
        "thirds reads on from the bottom third, THIRDS, NONE, 12, 0.1, 0.8, false, FORWARD",
        "thirds reads back from the top third, THIRDS, NONE, 12, 0.9, 0.2, false, BACKWARD",
        "thirds opens the menu from the middle, THIRDS, NONE, 12, 0.1, 0.5, false, MENU",
        // Right and left are a pager's sideways turns; on a page that scrolls down they read on and back.
        "right and left reads on from the right, RIGHT_AND_LEFT, NONE, 12, 0.9, 0.5, false, FORWARD",
        "right and left reads back from the left, RIGHT_AND_LEFT, NONE, 12, 0.1, 0.5, false, BACKWARD",
        // Pages turning right to left read on from the left edge, as Mihon's right-to-left pager does.
        "right to left pages read on from the left, RIGHT_AND_LEFT, NONE, 12, 0.1, 0.5, true, FORWARD",
        "right to left pages read back from the right, RIGHT_AND_LEFT, NONE, 12, 0.9, 0.5, true, BACKWARD",
        // Next and previous are not sideways, so a direction does not swap them.
        "right to left keeps the thirds next zone, THIRDS, NONE, 12, 0.1, 0.8, true, FORWARD",
        // The middle row's left third turns back, where thirds would open the menu.
        "L shaped reads back from the left of the middle, L_SHAPED, NONE, 12, 0.1, 0.5, false, BACKWARD",
        // The whole left column below the top row turns back, where thirds would read on at the bottom.
        "kindlish reads back from the lower left, KINDLISH, NONE, 12, 0.1, 0.8, false, BACKWARD",
        // Both edges read on, so turning back is the bottom of the middle column.
        "edge reads back from the bottom middle, EDGE, NONE, 12, 0.5, 0.9, false, BACKWARD",
        "inverting swaps where a layout reads on, THIRDS, VERTICAL, 12, 0.5, 0.8, false, BACKWARD",
        "a centre layout opens the menu inside its zone, CENTER, NONE, 12, 0.5, 0.5, false, MENU",
        "a centre layout leaves a tap outside its zone alone, CENTER, NONE, 12, 0.5, 0.2, false, ",
        "the large centre reaches wider than the centre, CENTER_LARGE, NONE, 12, 0.35, 0.5, false, MENU",
        "the centre stops short of the large centre, CENTER, NONE, 12, 0.35, 0.5, false, ",
        "the bottom zone is as tall as its setting, BOTTOM, NONE, 30, 0.5, 0.75, false, MENU",
        "a tap above the bottom zone is left alone, BOTTOM, NONE, 12, 0.5, 0.75, false, ",
        "a vertically inverted bottom zone sits at the top, BOTTOM, VERTICAL, 12, 0.5, 0.05, false, MENU",
    )
    fun `a tap reads through the layout's zones`(
        name: String,
        layout: NovelTapLayout,
        invert: TappingInvertMode,
        bottom: Int,
        x: Float,
        y: Float,
        rtl: Boolean,
        expected: Action?,
    ) {
        NovelTapZones(layout, invert, bottom).actionAt(x, y, rtl) shouldBe expected
    }

    /** Before a session's zones arrive, a renderer still answers a tap, by opening the menu. */
    @Test
    fun `no zones yet opens the menu`() {
        NovelTapZones.tapAction(null, 0.5f, 0.2f, rtl = false) shouldBe Action.MENU
    }

    /** A zone-only layout's miss stays the page's in both renderers, not turned into a menu toggle. */
    @Test
    fun `a tap outside a zone-only layout stays the page's`() {
        val zones = NovelTapZones(NovelTapLayout.CENTER, TappingInvertMode.NONE, 12)
        NovelTapZones.tapAction(zones, 0.5f, 0.2f, rtl = false) shouldBe null
    }
}
