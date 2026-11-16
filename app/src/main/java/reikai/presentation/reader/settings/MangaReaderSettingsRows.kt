package reikai.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import reikai.presentation.reader.showsTwoPages
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BaseSliderItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat
import tachiyomi.core.common.preference.Preference as PreferenceData

/**
 * Which rows Settings > Reader offers, so none is a control the renderer the reader runs ignores. With no
 * viewer to ask, two pages are known only under Always: under When wide a narrow screen still shows one.
 */
internal class MangaReaderSettingsRows(
    highQualityRenderer: Boolean,
    dualPageView: ReaderPreferences.DualPageView,
) {
    /** The high quality renderer's own settings, from the reader sheet. */
    val rendererRows = highQualityRenderer

    /** That renderer's double tap zooms at the library's own speed. */
    val doubleTapAnimSpeed = !highQualityRenderer

    /** A spread skips crop borders, zoom start and landscape zoom, and splits or turns no page. */
    val singlePageRows = !(highQualityRenderer && dualPageView == ReaderPreferences.DualPageView.ALWAYS)

    /** The dual-page variants of transition animation and cutout mode, and the space between pages. */
    val dualPageRows = dualPageRowsShown(highQualityRenderer, dualPageView)

    /** That renderer's alternative to zooming into the borders the paged Crop borders measures. */
    fun cropInsteadOfZoom(cropBorders: Boolean) = rendererRows && cropBorders && singlePageRows
}

/** The transitions upstream's sheet offers for a two-page spread: all but Flip left and Flip right. */
internal val dualPageTransitionAnimations = ReaderPreferences.TransitionAnimation.entries -
    ReaderPreferences.TransitionAnimation.FLIP_LEFT -
    ReaderPreferences.TransitionAnimation.FLIP_RIGHT

/**
 * Whether Settings offers the rows only a spread reads: a reader that [canSpread] (manga's high quality renderer,
 * a paged novel) with Dual page view not Never. Settings has no screen to ask, so When wide counts.
 */
internal fun dualPageRowsShown(canSpread: Boolean, dualPageView: ReaderPreferences.DualPageView): Boolean =
    canSpread && dualPageView != ReaderPreferences.DualPageView.NEVER

/** The reader sheets' version: a [paged] reader whose Dual page view shows two pages on this screen. */
internal fun sheetDualPageRowsShown(
    paged: Boolean,
    dualPageView: ReaderPreferences.DualPageView,
    screenWidthDp: Int,
    screenHeightDp: Int,
): Boolean = paged && dualPageView.showsTwoPages(screenWidthDp, screenHeightDp)

/** The space between a spread's two pages, in percent of a page's width, for manga and novels alike. */
internal val spreadGapRange = 0..20

/** Space between pages in Settings > Manga reader and Settings > Novel reader, over each type's own setting. */
@Composable
internal fun spreadGapRow(preference: PreferenceData<Int>, visible: Boolean) =
    Preference.PreferenceItem.SliderPreference(
        preference = preference,
        title = stringResource(MR.strings.pref_spread_gap),
        valueText = { remember { NumberFormat.getPercentInstance() }.format(it / 100f) },
        valueRange = spreadGapRange,
        visible = visible,
    )

/** Space between pages in a reader sheet. Written as a drag lets go: each value lays the open spread out again. */
@Composable
internal fun SpreadGapSlider(preference: PreferenceData<Int>) {
    val stored by preference.collectAsState()
    var dragged by remember { mutableStateOf<Int?>(null) }
    val numberFormat = remember { NumberFormat.getPercentInstance() }
    val value = dragged ?: stored
    BaseSliderItem(
        value = value,
        valueRange = spreadGapRange,
        title = stringResource(MR.strings.pref_spread_gap),
        valueString = numberFormat.format(value / 100f),
        onChange = { dragged = it },
        titleStyle = MaterialTheme.typography.bodyMedium,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.padding(
            horizontal = SettingsItemsPaddings.Horizontal,
            vertical = SettingsItemsPaddings.Vertical,
        ),
        onChangeFinished = {
            dragged?.let(preference::set)
            dragged = null
        },
    )
}

/**
 * Dual page view in a paged reader's sheet, for manga and novels alike, over each type's own setting, with
 * the space between pages beneath it while this screen shows two.
 */
@Composable
internal fun ColumnScope.DualPageViewRows(
    dualPageView: PreferenceData<ReaderPreferences.DualPageView>,
    spreadGap: PreferenceData<Int>,
) {
    val selected by dualPageView.collectAsState()
    SettingsChipRow(MR.strings.pref_dual_page_view) {
        ReaderPreferences.DualPageView.entries.forEach {
            FilterChip(
                selected = it == selected,
                onClick = { dualPageView.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }
    val configuration = LocalConfiguration.current
    if (selected.showsTwoPages(configuration.screenWidthDp, configuration.screenHeightDp)) {
        SpreadGapSlider(spreadGap)
    }
}

/** Dual page view's row in Settings > Manga reader and Settings > Novel reader, over each type's own setting. */
@Composable
internal fun dualPageViewRow(preference: PreferenceData<ReaderPreferences.DualPageView>) =
    Preference.PreferenceItem.ListPreference(
        preference = preference,
        entries = ReaderPreferences.DualPageView.entries.associateWith { stringResource(it.titleRes) },
        title = stringResource(MR.strings.pref_dual_page_view),
    )

/** The high quality renderer's own settings, which otherwise live only in the reader sheet. */
@Composable
internal fun webGpuViewerGroup(
    preferences: ReaderPreferences,
    rows: MangaReaderSettingsRows,
): Preference.PreferenceGroup {
    return Preference.PreferenceGroup(
        title = stringResource(MR.strings.pref_category_high_quality_renderer),
        visible = rows.rendererRows,
        preferenceItems = listOf(
            dualPageViewRow(preferences.dualPageView),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.transitionAnimation,
                entries = ReaderPreferences.TransitionAnimation.entries.associateWith { stringResource(it.titleRes) },
                title = stringResource(MR.strings.pref_transition_animation),
                visible = rows.singlePageRows,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.transitionAnimationDual,
                entries = dualPageTransitionAnimations.associateWith { stringResource(it.titleRes) },
                title = stringResource(MR.strings.pref_transition_animation_dual),
                visible = rows.dualPageRows,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.cutoutMode,
                entries = ReaderPreferences.CutoutMode.entries.associateWith { stringResource(it.titleRes) },
                title = stringResource(MR.strings.pref_cutout_mode),
                visible = rows.singlePageRows,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.cutoutModeDual,
                entries = ReaderPreferences.CutoutMode.entries.associateWith { stringResource(it.titleRes) },
                title = stringResource(MR.strings.pref_cutout_mode_dual),
                visible = rows.dualPageRows,
            ),
            spreadGapRow(preferences.spreadGap, visible = rows.dualPageRows),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.disableDoubleTapGestures,
                title = stringResource(MR.strings.pref_disable_double_tap_gestures),
            ),
        ),
    )
}
