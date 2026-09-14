package reikai.domain.novel

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import tachiyomi.i18n.MR

/**
 * How the page is divided for a tap. A zone-only layout draws just a menu zone, and a tap outside it is
 * left to the page rather than opening the menu, so reading can be touched without the chrome flashing.
 */
enum class NovelTapLayout(val titleRes: StringResource, val isZoneOnly: Boolean = false) {
    DISABLED(MR.strings.disabled_nav),
    THIRDS(MR.strings.nav_thirds),
    L_SHAPED(MR.strings.l_nav),
    KINDLISH(MR.strings.kindlish_nav),
    EDGE(MR.strings.edge_nav),
    RIGHT_AND_LEFT(MR.strings.right_and_left_nav),
    CENTER(MR.strings.nav_center, isZoneOnly = true),
    CENTER_LARGE(MR.strings.nav_center_large, isZoneOnly = true),
    BOTTOM(MR.strings.nav_bottom, isZoneOnly = true),
    ;

    /** The inversions that change this layout: the centre zones mirror onto themselves, and the
     *  full-width bottom zone only flips vertically. */
    val invertModes: List<TappingInvertMode>
        get() = when (this) {
            CENTER, CENTER_LARGE -> listOf(TappingInvertMode.NONE)
            BOTTOM -> listOf(TappingInvertMode.NONE, TappingInvertMode.VERTICAL)
            else -> TappingInvertMode.entries
        }
}
