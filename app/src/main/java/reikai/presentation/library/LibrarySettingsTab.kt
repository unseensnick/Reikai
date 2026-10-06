package reikai.presentation.library

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** The library settings sheet's tabs, in the order the sheet shows them. */
enum class LibrarySettingsTab(val titleRes: StringResource) {
    FILTER(MR.strings.action_filter),
    SORT(MR.strings.action_sort),
    DISPLAY(MR.strings.action_display),
    GROUP(MR.strings.group),
}

/**
 * What a long press on the hopper's middle button does. [code] is what `hopper_long_press` stores and
 * backups carry, so an entry keeps its code for good; [fromCode] answers null for a code no entry
 * holds, which does nothing, as an unknown stored value always has.
 */
enum class HopperLongPressAction(val code: Int, val labelRes: StringResource) {
    SEARCH(0, MR.strings.hopper_action_search),
    EXPAND_COLLAPSE(1, MR.strings.hopper_action_expand_collapse),
    DISPLAY(2, MR.strings.hopper_action_display),
    GROUP(3, MR.strings.hopper_action_group),
    RANDOM(4, MR.strings.hopper_action_random),
    RANDOM_GLOBAL(5, MR.strings.hopper_action_random_global),
    ;

    companion object {
        fun fromCode(code: Int): HopperLongPressAction? = entries.find { it.code == code }
    }
}
