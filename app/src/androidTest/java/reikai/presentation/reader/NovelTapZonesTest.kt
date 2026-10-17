package reikai.presentation.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import reikai.domain.novel.NovelTapLayout

/**
 * The rule both novel renderers read a tap through. On a device because the layouts are Mihon's, drawn
 * in `RectF`, which a JVM test only has as a stub.
 */
@RunWith(AndroidJUnit4::class)
class NovelTapZonesTest {

    private fun zones(layout: NovelTapLayout, invert: TappingInvertMode = TappingInvertMode.NONE, bottom: Int = 12) =
        NovelTapZones(layout, invert, bottom)

    @Test
    fun disabledOpensTheMenuWherever() {
        assertEquals(NovelTapAction.MENU, zones(NovelTapLayout.DISABLED).actionAt(0.1f, 0.9f, rtl = false))
    }

    @Test
    fun thirdsReadsOnFromTheBottomThird() {
        assertEquals(NovelTapAction.FORWARD, zones(NovelTapLayout.THIRDS).actionAt(0.1f, 0.8f, rtl = false))
    }

    @Test
    fun thirdsReadsBackFromTheTopThird() {
        assertEquals(NovelTapAction.BACK, zones(NovelTapLayout.THIRDS).actionAt(0.9f, 0.2f, rtl = false))
    }

    @Test
    fun thirdsOpensTheMenuFromTheMiddle() {
        assertEquals(NovelTapAction.MENU, zones(NovelTapLayout.THIRDS).actionAt(0.1f, 0.5f, rtl = false))
    }

    /** Right and left are a pager's sideways turns; on a page that scrolls down they read on and back. */
    @Test
    fun rightAndLeftReadsOnFromTheRight() {
        assertEquals(NovelTapAction.FORWARD, zones(NovelTapLayout.RIGHT_AND_LEFT).actionAt(0.9f, 0.5f, rtl = false))
    }

    @Test
    fun rightAndLeftReadsBackFromTheLeft() {
        assertEquals(NovelTapAction.BACK, zones(NovelTapLayout.RIGHT_AND_LEFT).actionAt(0.1f, 0.5f, rtl = false))
    }

    /** Pages turning right to left read on from the left edge, as Mihon's right-to-left pager does. */
    @Test
    fun rightToLeftPagesReadOnFromTheLeft() {
        assertEquals(NovelTapAction.FORWARD, zones(NovelTapLayout.RIGHT_AND_LEFT).actionAt(0.1f, 0.5f, rtl = true))
    }

    @Test
    fun rightToLeftPagesReadBackFromTheRight() {
        assertEquals(NovelTapAction.BACK, zones(NovelTapLayout.RIGHT_AND_LEFT).actionAt(0.9f, 0.5f, rtl = true))
    }

    /** Next and previous are not sideways, so a direction does not swap them. */
    @Test
    fun rightToLeftKeepsTheThirdsNextZone() {
        assertEquals(NovelTapAction.FORWARD, zones(NovelTapLayout.THIRDS).actionAt(0.1f, 0.8f, rtl = true))
    }

    /** The middle row's left third turns back, where thirds would open the menu. */
    @Test
    fun lShapedReadsBackFromTheLeftOfTheMiddle() {
        assertEquals(NovelTapAction.BACK, zones(NovelTapLayout.L_SHAPED).actionAt(0.1f, 0.5f, rtl = false))
    }

    /** The whole left column below the top row turns back, where thirds would read on at the bottom. */
    @Test
    fun kindlishReadsBackFromTheLowerLeft() {
        assertEquals(NovelTapAction.BACK, zones(NovelTapLayout.KINDLISH).actionAt(0.1f, 0.8f, rtl = false))
    }

    /** Both edges read on, so turning back is the bottom of the middle column. */
    @Test
    fun edgeReadsBackFromTheBottomMiddle() {
        assertEquals(NovelTapAction.BACK, zones(NovelTapLayout.EDGE).actionAt(0.5f, 0.9f, rtl = false))
    }

    @Test
    fun invertingSwapsWhereALayoutReadsOn() {
        assertEquals(
            NovelTapAction.BACK,
            zones(NovelTapLayout.THIRDS, TappingInvertMode.VERTICAL).actionAt(0.5f, 0.8f, rtl = false),
        )
    }

    @Test
    fun aCentreLayoutOpensTheMenuInsideItsZone() {
        assertEquals(NovelTapAction.MENU, zones(NovelTapLayout.CENTER).actionAt(0.5f, 0.5f, rtl = false))
    }

    /** Outside the zone a tap is the page's, where Mihon's own lookup would have opened the menu. */
    @Test
    fun aCentreLayoutLeavesATapOutsideItsZoneAlone() {
        assertEquals(NovelTapAction.NONE, zones(NovelTapLayout.CENTER).actionAt(0.5f, 0.2f, rtl = false))
    }

    @Test
    fun theLargeCentreReachesWiderThanTheCentre() {
        assertEquals(NovelTapAction.MENU, zones(NovelTapLayout.CENTER_LARGE).actionAt(0.35f, 0.5f, rtl = false))
    }

    @Test
    fun theBottomZoneIsAsTallAsItsSetting() {
        assertEquals(NovelTapAction.MENU, zones(NovelTapLayout.BOTTOM, bottom = 30).actionAt(0.5f, 0.75f, rtl = false))
    }

    @Test
    fun aTapAboveTheBottomZoneIsLeftAlone() {
        assertEquals(NovelTapAction.NONE, zones(NovelTapLayout.BOTTOM, bottom = 12).actionAt(0.5f, 0.75f, rtl = false))
    }

    @Test
    fun aVerticallyInvertedBottomZoneSitsAtTheTop() {
        assertEquals(
            NovelTapAction.MENU,
            zones(NovelTapLayout.BOTTOM, TappingInvertMode.VERTICAL).actionAt(0.5f, 0.05f, rtl = false),
        )
    }
}
