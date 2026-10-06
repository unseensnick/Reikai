package reikai.presentation.reader

import androidx.compose.runtime.Composable
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

/** How far a volume key scrolls, the same row on both readers' settings screens. */
@Composable
fun volumeKeyScrollPreference(
    preference: Preference<Float>,
    enabled: Boolean,
    subtitle: String?,
) = SettingsPreference.PreferenceItem.SliderPreference(
    preference = preference.scaled(ReaderRanges.PERCENT),
    valueRange = ReaderRanges.volumeKeyScrollPercent,
    title = stringResource(MR.strings.pref_volume_keys_scroll_amount),
    subtitle = subtitle,
    valueText = { "$it%" },
    enabled = enabled,
)
