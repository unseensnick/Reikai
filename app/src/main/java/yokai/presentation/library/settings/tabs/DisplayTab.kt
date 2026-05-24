package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMFORTABLE_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMPACT_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_LIST
import kotlin.math.roundToInt
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.ui.UiPreferences
import yokai.i18n.MR
import yokai.presentation.component.preference.widget.ListPreferenceWidget
import yokai.presentation.component.preference.widget.SliderPreferenceWidget
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget

/**
 * Display tab: layout chooser + the four grid-shape switches + grid size slider. Same set of
 * preferences the legacy [eu.kanade.tachiyomi.ui.library.display.LibraryDisplayView] writes;
 * dropping the explicit "reset grid size" button since the slider's min position serves the
 * same purpose.
 */
@Composable
fun DisplayTab() {
    val preferences: PreferencesHelper = remember { Injekt.get() }
    val uiPreferences: UiPreferences = remember { Injekt.get() }

    val libraryLayout by preferences.libraryLayout().collectAsState()
    val uniformGrid by uiPreferences.uniformGrid().collectAsState()
    val outlineOnCovers by uiPreferences.outlineOnCovers().collectAsState()
    val useStaggeredGrid by preferences.useStaggeredGrid().collectAsState()
    // Backing pref is a Float in roughly [-0.5, 3]; the legacy slider maps that to 0..7 via
    // ((value + 0.5) * 2). Mirror that mapping here so the saved value remains compatible
    // with the legacy display sheet.
    val gridSizeFloat by preferences.gridSize().collectAsState()
    val gridSliderValue = ((gridSizeFloat + 0.5f) * 2f).roundToInt().coerceIn(0, 7)

    val layoutEntries: Map<Int, String> = mapOf(
        LAYOUT_LIST to stringResource(MR.strings.list),
        LAYOUT_COMPACT_GRID to stringResource(MR.strings.compact_grid),
        LAYOUT_COMFORTABLE_GRID to stringResource(MR.strings.comfortable_grid),
        LAYOUT_COVER_ONLY_GRID to stringResource(MR.strings.cover_only_grid),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        ListPreferenceWidget(
            value = libraryLayout,
            title = stringResource(MR.strings.display),
            subtitle = layoutEntries[libraryLayout],
            icon = null,
            entries = layoutEntries,
            onValueChange = { preferences.libraryLayout().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.uniform_grid_covers),
            checked = uniformGrid,
            onCheckedChanged = { uiPreferences.uniformGrid().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.use_staggered_grid),
            checked = useStaggeredGrid,
            onCheckedChanged = { preferences.useStaggeredGrid().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_outline_around_covers),
            checked = outlineOnCovers,
            onCheckedChanged = { uiPreferences.outlineOnCovers().set(it) },
        )
        SliderPreferenceWidget(
            title = stringResource(MR.strings.grid_size),
            value = gridSliderValue,
            min = 0,
            max = 7,
            onValueChange = { preferences.gridSize().set((it / 2f) - 0.5f) },
        )
    }
}
