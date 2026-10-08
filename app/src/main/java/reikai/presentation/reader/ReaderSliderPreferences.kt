package reikai.presentation.reader

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderHideThreshold
import reikai.util.scaled
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import eu.kanade.presentation.more.settings.Preference as SettingsPreference

/** A value counted in tenths, shown through [format] as the float it stands for. */
fun tenthsLabel(tenths: Int, format: String): String = format.format(tenths.toFloat() / ReaderRanges.TENTHS)

fun autoScrollSpeedLabel(tenths: Int): String = tenthsLabel(tenths, "%.1fx")

/** Auto-scroll's speed, the same row on both readers' settings screens. */
@Composable
fun autoScrollSpeedPreference(
    preference: Preference<Float>,
    subtitle: String?,
) = SettingsPreference.PreferenceItem.SliderPreference(
    preference = preference.scaled(ReaderRanges.TENTHS),
    valueRange = ReaderRanges.autoScrollSpeedTenths,
    title = stringResource(MR.strings.pref_auto_scroll_speed),
    subtitle = subtitle,
    valueText = { autoScrollSpeedLabel(it) },
)

/** How far a scroll must go before the menu hides, the same row on both readers' settings screens. */
@Composable
fun hideThresholdPreference(preference: Preference<ReaderHideThreshold>) =
    SettingsPreference.PreferenceItem.ListPreference(
        preference = preference,
        entries = mapOf(
            ReaderHideThreshold.HIGHEST to stringResource(MR.strings.pref_highest),
            ReaderHideThreshold.HIGH to stringResource(MR.strings.pref_high),
            ReaderHideThreshold.LOW to stringResource(MR.strings.pref_low),
            ReaderHideThreshold.LOWEST to stringResource(MR.strings.pref_lowest),
        ),
        title = stringResource(MR.strings.pref_hide_threshold),
    )

/** Which side the vertical rail sits on, the same row on both readers' settings screens. */
@Composable
fun railOnLeftPreference(preference: Preference<Boolean>, visible: Boolean) =
    SettingsPreference.PreferenceItem.SwitchPreference(
        preference = preference,
        title = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
        visible = visible,
    )

/** The vertical rail's height, the same row on both readers' settings screens. */
@Composable
fun railHeightPreference(preference: Preference<Int>, visible: Boolean) =
    SettingsPreference.PreferenceItem.SliderPreference(
        preference = preference,
        valueRange = ReaderRanges.railHeightPercent,
        steps = ReaderRanges.railHeightSteps,
        title = stringResource(MR.strings.pref_vertical_navigator_height),
        visible = visible,
    )

/** How far a volume key scrolls, the same row on both readers' settings screens. */
@Composable
fun volumeKeyScrollPreference(
    preference: Preference<Float>,
    visible: Boolean,
    subtitle: String?,
) = SettingsPreference.PreferenceItem.SliderPreference(
    preference = preference.scaled(ReaderRanges.PERCENT),
    valueRange = ReaderRanges.volumeKeyScrollPercent,
    title = stringResource(MR.strings.pref_volume_keys_scroll_amount),
    subtitle = subtitle,
    valueText = { "$it%" },
    visible = visible,
)
