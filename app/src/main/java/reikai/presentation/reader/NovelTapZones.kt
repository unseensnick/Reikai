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
    private val navigation: ViewerNavigation by lazy {
        when (layout) {
            NovelTapLayout.DISABLED -> DisabledNavigation()
            NovelTapLayout.THIRDS -> ThirdsNavigation()
            NovelTapLayout.L_SHAPED -> LNavigation()
            NovelTapLayout.KINDLISH -> KindlishNavigation()
            NovelTapLayout.EDGE -> EdgeNavigation()
            NovelTapLayout.RIGHT_AND_LEFT -> RightAndLeftNavigation()
            NovelTapLayout.CENTER -> CenterNavigation()
            NovelTapLayout.CENTER_LARGE -> CenterNavigation(large = true)
            NovelTapLayout.BOTTOM -> BottomNavigation(bottomZoneHeightPercent / 100f)
        }.also { it.invertMode = invert }
    }

    /** What a tap at [x], [y], each a fraction of the page, asks for. Next and right both read on. */
    fun actionAt(x: Float, y: Float): NovelTapAction {
        if (layout.isZoneOnly) {
            val inZone = navigation.getRegions().any { it.rectF.contains(x, y) }
            return if (inZone) NovelTapAction.MENU else NovelTapAction.NONE
        }
        return when (navigation.getAction(PointF(x, y))) {
            NavigationRegion.MENU -> NovelTapAction.MENU
            NavigationRegion.NEXT, NavigationRegion.RIGHT -> NovelTapAction.FORWARD
            NavigationRegion.PREV, NavigationRegion.LEFT -> NovelTapAction.BACK
        }
    }

    companion object {
        /** How much of the screen a tap that reads on or back moves, in both renderers. */
        const val SCROLL_FRACTION = 0.75f

        /** The bottom zone's height a setting may choose, as `BottomNavigation` bounds it. */
        val BOTTOM_ZONE_PERCENT = 2..50
    }
}
