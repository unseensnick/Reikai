package reikai.presentation.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderHideThreshold
import reikai.util.scaled
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import eu.kanade.presentation.more.settings.Preference as SettingsPreference

/** A value counted in tenths, shown through [format] as the float it stands for. */
fun tenthsLabel(tenths: Int, format: String): String = format.format(tenths.toFloat() / ReaderRanges.TENTHS)

fun autoScrollSpeedLabel(tenths: Int): String = tenthsLabel(tenths, "%.1fx")

/** How long auto-scroll shows each page before turning it, the same row on both readers' settings screens. */
@Composable
fun autoScrollIntervalPreference(preference: Preference<Int>) = SettingsPreference.PreferenceItem.SliderPreference(
    preference = preference,
    valueRange = ReaderRanges.autoScrollIntervalSeconds,
    title = stringResource(MR.strings.pref_auto_scroll_interval),
    subtitle = stringResource(MR.strings.pref_auto_scroll_paged_only),
    valueText = { stringResource(MR.strings.seconds_short, it) },
)

/** The e-ink flash after a page turn, the same group on both readers' settings screens. */
@Composable
fun readerFlashGroup(flash: ReaderFlash): SettingsPreference.PreferenceGroup {
    val flashPageState by flash.onPageChange.collectAsState()
    return SettingsPreference.PreferenceGroup(
        title = "E-Ink",
        preferenceItems = listOf(
            SettingsPreference.PreferenceItem.SwitchPreference(
                preference = flash.onPageChange,
                title = stringResource(MR.strings.pref_flash_page),
                subtitle = stringResource(MR.strings.pref_flash_page_summ),
            ),
            SettingsPreference.PreferenceItem.SliderPreference(
                preference = flash.durationMillis,
                valueRange = ReaderPreferences.MILLI_CONVERSION.let { it..it * 15 step it },
                title = stringResource(MR.strings.pref_flash_duration),
                valueText = { stringResource(MR.strings.pref_flash_duration_summary, it) },
                visible = flashPageState,
            ),
            SettingsPreference.PreferenceItem.SliderPreference(
                preference = flash.pageInterval,
                valueRange = 1..10,
                title = stringResource(MR.strings.pref_flash_page_interval),
                valueText = { pluralStringResource(MR.plurals.pref_pages, it, it) },
                visible = flashPageState,
            ),
            SettingsPreference.PreferenceItem.ListPreference(
                preference = flash.color,
                entries = mapOf(
                    ReaderPreferences.FlashColor.BLACK to stringResource(MR.strings.pref_flash_style_black),
                    ReaderPreferences.FlashColor.WHITE to stringResource(MR.strings.pref_flash_style_white),
                    ReaderPreferences.FlashColor.WHITE_BLACK
                        to stringResource(MR.strings.pref_flash_style_white_black),
                ),
                title = stringResource(MR.strings.pref_flash_with),
                visible = flashPageState,
            ),
        ),
    )
}

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
