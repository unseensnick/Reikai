package reikai.domain.novel

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR

/**
 * How the page is divided for a tap. A zone-only layout draws just a menu zone, and a tap outside it is
 * left to the page rather than opening the menu, so reading can be touched without the chrome flashing.
 */
enum class NovelTapLayout(val titleRes: StringResource) {
    DISABLED(MR.strings.disabled_nav),
    THIRDS(MR.strings.nav_thirds),
    L_SHAPED(MR.strings.l_nav),
    KINDLISH(MR.strings.kindlish_nav),
    EDGE(MR.strings.edge_nav),
    RIGHT_AND_LEFT(MR.strings.right_and_left_nav),
    CENTER(MR.strings.nav_center),
    CENTER_LARGE(MR.strings.nav_center_large),
    BOTTOM(MR.strings.nav_bottom),
    ;

    /** The inversions that change this layout: the centre zones mirror onto themselves, and the
     *  full-width bottom zone only flips vertically. */
    val invertModes: List<TappingInvertMode>
        get() = when (this) {
            CENTER, CENTER_LARGE -> listOf(TappingInvertMode.NONE)
            BOTTOM -> listOf(TappingInvertMode.NONE, TappingInvertMode.VERTICAL)
            else -> TappingInvertMode.entries
        }

    /** Whether the inversion choice is offered: Disabled has no zones to invert, and one inversion is no choice. */
    val offersInvert: Boolean
        get() = this != DISABLED && invertModes.size > 1

    /** [invert] when this layout can draw it, else the nearest inversion it can: the vertical half, or none. */
    fun nearestInvert(invert: TappingInvertMode): TappingInvertMode = when {
        invert in invertModes -> invert
        invert.shouldInvertVertical && TappingInvertMode.VERTICAL in invertModes -> TappingInvertMode.VERTICAL
        else -> TappingInvertMode.NONE
    }
}

/**
 * Replaces an inversion [layout] cannot draw with its [NovelTapLayout.nearestInvert], so the choice a settings row
 * shows is the one in effect, in either reader. One it can draw is left unwritten, so the key stays unset.
 */
fun Preference<TappingInvertMode>.fitTo(layout: NovelTapLayout) {
    val nearest = layout.nearestInvert(get())
    if (nearest != get()) set(nearest)
}
