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
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.base.BasePreferences
import yokai.domain.novel.NovelPreferences
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
fun DisplayTab(
    /** True when this tab is rendered inside the Novels-tab Display sheet. Routes all five
     *  visual prefs (layout, grid size, staggered, uniform grid, outline) through
     *  `novelPrefs.*` when the shared toggle is off, so changes only affect the novel library. */
    isNovelTab: Boolean = false,
) {
    val preferences: PreferencesHelper = remember { Injekt.get() }
    val uiPreferences: UiPreferences = remember { Injekt.get() }
    val novelPrefs: NovelPreferences = remember { Injekt.get() }
    val basePrefs: BasePreferences = remember { Injekt.get() }
    val useSharedLibraryDisplayPrefs by basePrefs.useSharedLibraryDisplayPrefs().collectAsState()
    val routeToNovel = isNovelTab && !useSharedLibraryDisplayPrefs

    val libraryLayoutPref = rememberRoutedPref(routeToNovel, preferences.libraryLayout(), novelPrefs.novelLibraryLayout())
    val libraryLayout by libraryLayoutPref.collectAsState()
    val uniformGridPref = rememberRoutedPref(routeToNovel, uiPreferences.uniformGrid(), novelPrefs.novelUniformGrid())
    val uniformGrid by uniformGridPref.collectAsState()
    val outlineOnCoversPref = rememberRoutedPref(routeToNovel, uiPreferences.outlineOnCovers(), novelPrefs.novelOutlineOnCovers())
    val outlineOnCovers by outlineOnCoversPref.collectAsState()
    val useStaggeredGridPref = rememberRoutedPref(routeToNovel, preferences.useStaggeredGrid(), novelPrefs.novelUseStaggeredGrid())
    val useStaggeredGrid by useStaggeredGridPref.collectAsState()
    val gridSizePref = rememberRoutedPref(routeToNovel, preferences.gridSize(), novelPrefs.novelGridSize())
    val gridSizeFloat by gridSizePref.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LayoutRadioRow(MR.strings.list, LAYOUT_LIST, libraryLayout) { libraryLayoutPref.set(it) }
        LayoutRadioRow(MR.strings.compact_grid, LAYOUT_COMPACT_GRID, libraryLayout) { libraryLayoutPref.set(it) }
        LayoutRadioRow(MR.strings.comfortable_grid, LAYOUT_COMFORTABLE_GRID, libraryLayout) { libraryLayoutPref.set(it) }
        LayoutRadioRow(MR.strings.cover_only_grid, LAYOUT_COVER_ONLY_GRID, libraryLayout) { libraryLayoutPref.set(it) }

        GridSizeRow(
            gridSizeFloat = gridSizeFloat,
            onValueChange = { gridSizePref.set(it) },
            // Legacy reset = slider midpoint (value 3), which corresponds to pref 1.0f.
            onReset = { gridSizePref.set(1f) },
        )

        SwitchPreferenceWidget(
            title = stringResource(MR.strings.uniform_grid_covers),
            checked = uniformGrid,
            onCheckedChanged = { uniformGridPref.set(it) },
        )
        // Custom row so the title can carry the styled BETA tag; SwitchPreferenceWidget only
        // takes a plain String. Reuses TextPreferenceWidget's titleAnnotated slot.
        // Disabled when uniform grid is on: render-side already gives uniformGrid precedence over
        // staggered, so the toggle would be inert anyway. Mirrors LibraryDisplayView setting
        // staggeredGrid.isEnabled = !uniformGrid.
        TextPreferenceWidget(
            titleAnnotated = stringResource(MR.strings.use_staggered_grid).addBetaTag(useSuperScript = false),
            widget = {
                Switch(
                    checked = useStaggeredGrid,
                    onCheckedChange = null,
                    enabled = !uniformGrid,
                    modifier = Modifier.padding(start = 16.dp),
                )
            },
            onPreferenceClick = { useStaggeredGridPref.set(!useStaggeredGrid) },
            enabled = !uniformGrid,
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_outline_around_covers),
            checked = outlineOnCovers,
            onCheckedChanged = { outlineOnCoversPref.set(it) },
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
    val config = LocalConfiguration.current
    // Several consecutive slider stops can map to the same column count in one orientation,
    // which reads as "duplicate steps". Showing both Portrait and Landscape labels makes the
    // alt axis differences visible so the user can tell adjacent stops apart. Mirrors the
    // legacy slider labelFormatter ("Portrait: X • Landscape: Y").
    val portraitWidth = minOf(config.screenWidthDp, config.screenHeightDp)
    val landscapeWidth = maxOf(config.screenWidthDp, config.screenHeightDp)
    val portraitCols = columnsForGridValue(sliderValue, portraitWidth)
    val landscapeCols = columnsForGridValue(sliderValue, landscapeWidth)
    val portraitLabel = stringResource(MR.strings.portrait)
    val landscapeLabel = stringResource(MR.strings.landscape)

    // Komikku-style slider item: title + live size readout (and Reset) on top, full-width ticked
    // slider below, instead of the old cramped one-row layout.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(MR.strings.grid_size),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "$portraitLabel: $portraitCols • $landscapeLabel: $landscapeCols",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReset) {
                Text(stringResource(MR.strings.reset))
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = { v -> onValueChange((v / 2f) - 0.5f) },
            valueRange = 0f..7f,
            // 6 intermediate steps between 0 and 7 -> ticks at every integer.
            steps = 6,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Direct port of [eu.kanade.tachiyomi.util.view.numberOfRowsForValue] for the grid-size slider.
 * Returns the column count for a given screen-width-in-dp at the given slider value (0..7).
 *
 * Formula (matches legacy):
 *   value    = (rawValue / 2) - 0.5
 *   size     = 1.5 ^ value
 *   trueSize = MULTIPLE * round(size * 100 / MULTIPLE) / 100, with MULTIPLE = 25
 *   columns  = round((widthDp / 100) / trueSize), floored to 1
 *
 * The discretisation to MULTIPLE / 100 (i.e. 0.25 increments) is what makes adjacent slider
 * stops occasionally land on the same column count; showing both orientations in the subtitle
 * is the legacy's mitigation, and we follow suit.
 */
internal fun columnsForGridValue(rawValue: Float, screenWidthDp: Int): Int {
    val multiple = 25f
    val value = (rawValue / 2f) - 0.5f
    val size = 1.5f.pow(value)
    val trueSize = multiple * (size * 100f / multiple).roundToInt() / 100f
    val dpUnits = (screenWidthDp / 100f).roundToInt()
    return max(1, (dpUnits / trueSize).roundToInt())
}
