package reikai.presentation.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

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

    /** The dual-page variants of transition animation and cutout mode. */
    val dualPageRows = highQualityRenderer && dualPageView != ReaderPreferences.DualPageView.NEVER

    /** That renderer's alternative to zooming into the borders the paged Crop borders measures. */
    fun cropInsteadOfZoom(cropBorders: Boolean) = rendererRows && cropBorders && singlePageRows
}

/** The transitions upstream's sheet offers for a two-page spread: all but Flip left and Flip right. */
internal val dualPageTransitionAnimations = ReaderPreferences.TransitionAnimation.entries -
    ReaderPreferences.TransitionAnimation.FLIP_LEFT -
    ReaderPreferences.TransitionAnimation.FLIP_RIGHT

/** [offered] scale types keyed by the stored value, which counts from 1 through the full list. */
internal fun scaleTypeValues(offered: List<StringResource>): Map<Int, StringResource> =
    offered.associateBy { ReaderPreferences.ImageScaleType.indexOf(it) + 1 }

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
            Preference.PreferenceItem.ListPreference(
                preference = preferences.dualPageView,
                entries = ReaderPreferences.DualPageView.entries.associateWith { stringResource(it.titleRes) },
                title = stringResource(MR.strings.pref_dual_page_view),
            ),
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
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.disableDoubleTapGestures,
                title = stringResource(MR.strings.pref_disable_double_tap_gestures),
            ),
        ),
    )
}
