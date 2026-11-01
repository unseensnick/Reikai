package reikai.presentation.reader.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/*
 * The tap layouts Mihon's navigation set lacks, which both readers offer. Centre and bottom are tsundoku's
 * (CenterNavigation, BottomNavigation at b04f9a4d3); thirds is the rule Reikai's novel reader had
 * before it took the layouts.
 */

/** A layout that draws only a menu zone: a tap outside it is left to the page rather than opening the menu. */
interface ZoneOnlyNavigation

/** What a tap at [pos], in fractions of the page, asks for; null outside a [ZoneOnlyNavigation]'s zones. */
fun ViewerNavigation.actionAt(pos: Offset): ViewerNavigation.Action? =
    if (this is ZoneOnlyNavigation && getRegions().none { pos in it.rect }) null else getAction(pos)

/** The top third reads back, the bottom third reads on, and the middle band opens the menu. */
class ThirdsNavigation(invertMode: TappingInvertMode, isReversed: Boolean) : ViewerNavigation(invertMode, isReversed) {

    override val regionList: List<Region> = listOf(
        Region(rect = Rect(0f, 0f, 1f, 1f / 3), type = NavigationRegion.PREV),
        Region(rect = Rect(0f, 2f / 3, 1f, 1f), type = NavigationRegion.NEXT),
    )
}

/** Only the middle of the page opens the menu; [large] widens that zone. */
class CenterNavigation(private val large: Boolean, invertMode: TappingInvertMode, isReversed: Boolean) :
    ViewerNavigation(invertMode, isReversed),
    ZoneOnlyNavigation {

    override val regionList: List<Region> = listOf(
        Region(
            rect = if (large) Rect(0.3f, 0.3f, 0.7f, 0.7f) else Rect(0.4f, 0.4f, 0.6f, 0.6f),
            type = NavigationRegion.MENU,
        ),
    )

    // Upstream's equality reads the class alone, which would take a resized zone for no change.
    override fun equals(other: Any?): Boolean = super.equals(other) && (other as CenterNavigation).large == large

    override fun hashCode(): Int = 31 * super.hashCode() + large.hashCode()
}

/** Only a band along the bottom opens the menu, [heightFraction] of the page tall, as `NovelTapZones` bounds it. */
class BottomNavigation(private val heightFraction: Float, invertMode: TappingInvertMode, isReversed: Boolean) :
    ViewerNavigation(invertMode, isReversed),
    ZoneOnlyNavigation {

    override val regionList: List<Region> = listOf(
        Region(
            rect = Rect(0f, 1f - heightFraction, 1f, 1f),
            type = NavigationRegion.MENU,
        ),
    )

    override fun equals(other: Any?): Boolean =
        super.equals(other) && (other as BottomNavigation).heightFraction == heightFraction

    override fun hashCode(): Int = 31 * super.hashCode() + heightFraction.hashCode()
}
