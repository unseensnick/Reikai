package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMFORTABLE_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMPACT_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_LIST
import kotlin.math.pow
import kotlin.math.roundToInt
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.ui.UiPreferences
import yokai.i18n.MR
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget
import yokai.presentation.component.preference.widget.TextPreferenceWidget
import yokai.presentation.util.addBetaTag

/**
 * Display tab. Order and layout mirror the legacy
 * [eu.kanade.tachiyomi.ui.library.display.LibraryDisplayView]:
 *
 *  - Inline radio rows for layout (List / Compact / Comfortable / Cover-only) instead of a
 *    dropdown; matches the legacy RadioGroup.
 *  - Grid size row: title + "X per row" subtitle on the left, tick-marked slider in the middle,
 *    Reset button on the right.
 *  - Uniform grid covers (switch).
 *  - Use staggered grid (switch, with BETA suffix).
 *  - Show outline around covers (switch).
 */
@Composable
fun DisplayTab() {
    val preferences: PreferencesHelper = remember { Injekt.get() }
    val uiPreferences: UiPreferences = remember { Injekt.get() }

    val libraryLayout by preferences.libraryLayout().collectAsState()
    val uniformGrid by uiPreferences.uniformGrid().collectAsState()
    val outlineOnCovers by uiPreferences.outlineOnCovers().collectAsState()
    val useStaggeredGrid by preferences.useStaggeredGrid().collectAsState()
    val gridSizeFloat by preferences.gridSize().collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        LayoutRadioRow(MR.strings.list, LAYOUT_LIST, libraryLayout) { preferences.libraryLayout().set(it) }
        LayoutRadioRow(MR.strings.compact_grid, LAYOUT_COMPACT_GRID, libraryLayout) { preferences.libraryLayout().set(it) }
        LayoutRadioRow(MR.strings.comfortable_grid, LAYOUT_COMFORTABLE_GRID, libraryLayout) { preferences.libraryLayout().set(it) }
        LayoutRadioRow(MR.strings.cover_only_grid, LAYOUT_COVER_ONLY_GRID, libraryLayout) { preferences.libraryLayout().set(it) }

        GridSizeRow(
            gridSizeFloat = gridSizeFloat,
            onValueChange = { preferences.gridSize().set(it) },
            // Legacy reset = slider midpoint (value 3), which corresponds to pref 1.0f.
            onReset = { preferences.gridSize().set(1f) },
        )

        SwitchPreferenceWidget(
            title = stringResource(MR.strings.uniform_grid_covers),
            checked = uniformGrid,
            onCheckedChanged = { uiPreferences.uniformGrid().set(it) },
        )
        // Custom row so the title can carry the styled BETA tag; SwitchPreferenceWidget only
        // takes a plain String. Reuses TextPreferenceWidget's titleAnnotated slot.
        TextPreferenceWidget(
            titleAnnotated = stringResource(MR.strings.use_staggered_grid).addBetaTag(useSuperScript = false),
            widget = {
                Switch(
                    checked = useStaggeredGrid,
                    onCheckedChange = null,
                    modifier = Modifier.padding(start = 16.dp),
                )
            },
            onPreferenceClick = { preferences.useStaggeredGrid().set(!useStaggeredGrid) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_outline_around_covers),
            checked = outlineOnCovers,
            onCheckedChanged = { uiPreferences.outlineOnCovers().set(it) },
        )
    }
}

@Composable
private fun LayoutRadioRow(
    label: dev.icerock.moko.resources.StringResource,
    value: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected == value,
            onClick = null,
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun GridSizeRow(
    gridSizeFloat: Float,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    // Mirror the legacy slider mapping: pref Float is converted to a slider int 0..7 via
    // (pref + 0.5) * 2, so the default pref of 1.0f sits at slider position 3 (mid).
    val sliderValue = ((gridSizeFloat + 0.5f) * 2f).coerceIn(0f, 7f)
    val cellMinSizeDp = (128f * 1.5f.pow(gridSizeFloat)).roundToInt().coerceIn(72, 320)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = (screenWidthDp / cellMinSizeDp).coerceAtLeast(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                text = stringResource(MR.strings.grid_size),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(MR.strings._per_row, columns),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { v -> onValueChange((v / 2f) - 0.5f) },
            valueRange = 0f..7f,
            // 6 intermediate steps between 0 and 7 -> ticks at every integer.
            steps = 6,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReset) {
            Text(stringResource(MR.strings.reset))
        }
    }
}
