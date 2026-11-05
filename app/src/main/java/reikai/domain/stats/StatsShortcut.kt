package reikai.domain.stats

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** Where Statistics opens from besides More. Off by default, so the menus and the bar stay upstream's. */
enum class StatsShortcut(val titleRes: StringResource) {
    OFF(MR.strings.off),
    MENUS(MR.strings.pref_stats_shortcut_menus),
    TAB(MR.strings.pref_stats_shortcut_tab),
}
