package reikai.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import reikai.domain.novel.NovelTapLayout
import reikai.presentation.reader.NovelTapZones
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Either reader's tap layout, its inversion where it has something to invert, and the Bottom layout's zone height.
 * [layout] is the novel layout the [selected] one follows the rules of, null for one of Mihon's zoned layouts,
 * which every inversion changes.
 */
@Composable
internal fun ColumnScope.TapZonesRows(
    names: List<StringResource>,
    selected: Int,
    onSelect: (Int) -> Unit,
    layout: NovelTapLayout?,
    invert: TappingInvertMode,
    onInvert: (TappingInvertMode) -> Unit,
    bottomZoneHeight: Preference<Int>,
) {
    SettingsChipRow(MR.strings.pref_viewer_nav) {
        names.forEachIndexed { index, it ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(stringResource(it)) },
            )
        }
    }
    if (layout?.offersInvert != false) {
        SettingsChipRow(MR.strings.pref_read_with_tapping_inverted) {
            (layout?.invertModes ?: TappingInvertMode.entries).forEach {
                FilterChip(
                    selected = it == invert,
                    onClick = { onInvert(it) },
                    label = { Text(stringResource(it.titleRes)) },
                )
            }
        }
    }
    if (layout == NovelTapLayout.BOTTOM) {
        val height by bottomZoneHeight.collectAsState()
        SliderItem(
            value = height,
            valueRange = NovelTapZones.BOTTOM_ZONE_PERCENT,
            label = stringResource(MR.strings.pref_tap_bottom_zone_height),
            valueString = "$height%",
            onChange = bottomZoneHeight::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}
