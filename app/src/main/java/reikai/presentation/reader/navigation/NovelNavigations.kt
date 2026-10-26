package reikai.presentation.reader.navigation

import android.graphics.PointF
import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/*
 * The tap layouts Mihon's navigation set lacks, which both readers offer. Centre and bottom are tsundoku's
 * (CenterNavigation, BottomNavigation at b04f9a4d3); thirds is the rule Reikai's novel reader had
 * before it took the layouts.
 */

/** A layout that draws only a menu zone: a tap outside it is left to the page rather than opening the menu. */
interface ZoneOnlyNavigation

/** What a tap at [pos], in fractions of the page, asks for; null outside a [ZoneOnlyNavigation]'s zones. */
fun ViewerNavigation.actionAt(pos: PointF): ViewerNavigation.NavigationRegion? =
    if (this is ZoneOnlyNavigation && getRegions().none { it.rectF.contains(pos.x, pos.y) }) null else getAction(pos)

/** The top third reads back, the bottom third reads on, and the middle band opens the menu. */
class ThirdsNavigation : ViewerNavigation() {

    override var regionList: List<Region> = listOf(
        Region(rectF = RectF(0f, 0f, 1f, 1f / 3), type = NavigationRegion.PREV),
        Region(rectF = RectF(0f, 2f / 3, 1f, 1f), type = NavigationRegion.NEXT),
    )
}

/** Only the middle of the page opens the menu; [large] widens that zone. */
class CenterNavigation(large: Boolean = false) :
    ViewerNavigation(),
    ZoneOnlyNavigation {

    override var regionList: List<Region> = listOf(
        Region(
            rectF = if (large) RectF(0.3f, 0.3f, 0.7f, 0.7f) else RectF(0.4f, 0.4f, 0.6f, 0.6f),
            type = NavigationRegion.MENU,
        ),
    )
}

/** Only a band along the bottom opens the menu, [heightFraction] of the page tall, as `NovelTapZones` bounds it. */
class BottomNavigation(heightFraction: Float) :
    ViewerNavigation(),
    ZoneOnlyNavigation {

    override var regionList: List<Region> = listOf(
        Region(
            rectF = RectF(0f, 1f - heightFraction, 1f, 1f),
            type = NavigationRegion.MENU,
        ),
    )
}
