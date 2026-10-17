package reikai.presentation.reader

import android.graphics.PointF
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
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

enum class NovelTapAction { MENU, BACK, FORWARD, NONE }

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
    val navigation: ViewerNavigation by lazy {
        layout.navigation(bottomZoneHeightPercent).also { it.invertMode = invert }
    }

    /**
     * What a tap at [x], [y], each a fraction of the page, asks for. Next reads on; right reads on too, unless
     * the pages turn [rtl], where left does, as Mihon's right-to-left pager reads the same regions.
     */
    fun actionAt(x: Float, y: Float, rtl: Boolean): NovelTapAction {
        return when (navigation.actionAt(PointF(x, y))) {
            null -> NovelTapAction.NONE
            NavigationRegion.MENU -> NovelTapAction.MENU
            NavigationRegion.NEXT -> NovelTapAction.FORWARD
            NavigationRegion.PREV -> NovelTapAction.BACK
            NavigationRegion.RIGHT -> if (rtl) NovelTapAction.BACK else NovelTapAction.FORWARD
            NavigationRegion.LEFT -> if (rtl) NovelTapAction.FORWARD else NovelTapAction.BACK
        }
    }

    companion object {
        /** How much of the screen a tap that reads on or back moves, in both renderers. */
        const val SCROLL_FRACTION = 0.75f

        /** The bottom zone's height a setting may choose, in percent of the page. */
        val BOTTOM_ZONE_PERCENT = 2..50
    }
}

/** This layout's regions, for either reader; [bottomZoneHeightPercent] only shapes [NovelTapLayout.BOTTOM]. */
fun NovelTapLayout.navigation(bottomZoneHeightPercent: Int): ViewerNavigation = when (this) {
    NovelTapLayout.DISABLED -> DisabledNavigation()
    NovelTapLayout.THIRDS -> ThirdsNavigation()
    NovelTapLayout.L_SHAPED -> LNavigation()
    NovelTapLayout.KINDLISH -> KindlishNavigation()
    NovelTapLayout.EDGE -> EdgeNavigation()
    NovelTapLayout.RIGHT_AND_LEFT -> RightAndLeftNavigation()
    NovelTapLayout.CENTER -> CenterNavigation()
    NovelTapLayout.CENTER_LARGE -> CenterNavigation(large = true)
    NovelTapLayout.BOTTOM -> BottomNavigation(bottomZoneFraction(bottomZoneHeightPercent))
}

/** The bottom zone's share of the page, a stored height outside what the setting offers held to its ends. */
fun bottomZoneFraction(heightPercent: Int): Float = heightPercent.coerceIn(NovelTapZones.BOTTOM_ZONE_PERCENT) / 100f
