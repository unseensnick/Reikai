package reikai.domain.stats

import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR

/** Where Statistics opens from besides More. Off by default, so the menus and the bar stay upstream's. */
enum class StatsShortcut(val titleRes: StringResource) {
    OFF(MR.strings.off),
    MENUS(MR.strings.pref_stats_shortcut_menus),
    TAB(MR.strings.pref_stats_shortcut_tab),
}

/** Whether the Library, Updates and History overflow menus offer Statistics, kept current in [scope]. */
fun Preference<StatsShortcut>.inMenus(scope: CoroutineScope): StateFlow<Boolean> =
    changes().map { it == StatsShortcut.MENUS }
        .stateIn(scope, SharingStarted.Eagerly, get() == StatsShortcut.MENUS)
