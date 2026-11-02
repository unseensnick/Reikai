package reikai.presentation.reader

import androidx.compose.ui.geometry.Offset
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import reikai.domain.novel.NovelTapLayout
import reikai.presentation.reader.navigation.BottomNavigation
import reikai.presentation.reader.navigation.CenterNavigation
import reikai.presentation.reader.navigation.ThirdsNavigation
import reikai.presentation.reader.navigation.actionAt

/**
 * The tap zones a novel session reads, as one value so both renderers answer a tap from the same rule.
 * [bottomZoneHeightPercent] only shapes [NovelTapLayout.BOTTOM].
 */
data class NovelTapZones(
    val layout: NovelTapLayout,
    val invert: TappingInvertMode,
    val bottomZoneHeightPercent: Int,
) {
    /** The zones as regions, which the host's tap-zone overlay draws. */
    val navigation: ViewerNavigation by lazy { layout.navigation(bottomZoneHeightPercent, invert, isReversed = false) }

    // The same regions, read for a book that turns right to left.
    private val reversed: ViewerNavigation by lazy {
        layout.navigation(bottomZoneHeightPercent, invert, isReversed = true)
    }

    /**
     * What a tap at [x], [y], each a fraction of the page, asks for, null outside every zone. Next reads on;
     * right reads on too, unless the pages turn [rtl], where left does, as Mihon's right-to-left pager reads
     * the same regions.
     */
    fun actionAt(x: Float, y: Float, rtl: Boolean): ViewerNavigation.Action? =
        (if (rtl) reversed else navigation).actionAt(Offset(x, y))

    companion object {
        /** How much of the screen a tap that reads on or back moves, in both renderers. */
        const val SCROLL_FRACTION = 0.75f

        /** The bottom zone's height a setting may choose, in percent of the page. */
        val BOTTOM_ZONE_PERCENT = 2..50
    }
}

/** This layout's regions, for either reader; [bottomZoneHeightPercent] only shapes [NovelTapLayout.BOTTOM]. */
fun NovelTapLayout.navigation(
    bottomZoneHeightPercent: Int,
    invert: TappingInvertMode,
    isReversed: Boolean,
): ViewerNavigation = when (this) {
    NovelTapLayout.DISABLED -> DisabledNavigation(invert, isReversed)
    NovelTapLayout.THIRDS -> ThirdsNavigation(invert, isReversed)
    NovelTapLayout.L_SHAPED -> LNavigation(invert, isReversed)
    NovelTapLayout.KINDLISH -> KindlishNavigation(invert, isReversed)
    NovelTapLayout.EDGE -> EdgeNavigation(invert, isReversed)
    NovelTapLayout.RIGHT_AND_LEFT -> RightAndLeftNavigation(invert, isReversed)
    NovelTapLayout.CENTER -> CenterNavigation(large = false, invert, isReversed)
    NovelTapLayout.CENTER_LARGE -> CenterNavigation(large = true, invert, isReversed)
    NovelTapLayout.BOTTOM -> BottomNavigation(bottomZoneFraction(bottomZoneHeightPercent), invert, isReversed)
}

/** The bottom zone's share of the page, a stored height outside what the setting offers held to its ends. */
fun bottomZoneFraction(heightPercent: Int): Float = heightPercent.coerceIn(NovelTapZones.BOTTOM_ZONE_PERCENT) / 100f
