package reikai.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import reikai.domain.novel.NovelPreferences
import reikai.novel.font.NovelFont
import reikai.novel.font.fontDisplayName
import reikai.presentation.components.StepperItem
import reikai.presentation.icons.FormatAlignCenter
import reikai.presentation.icons.FormatAlignJustify
import reikai.presentation.icons.FormatAlignLeft
import reikai.presentation.icons.FormatAlignRight
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.reader.PresetSwatch
import reikai.presentation.reader.ReaderFont
import reikai.presentation.reader.readerFonts
import reikai.presentation.reader.readerGenericFonts
import reikai.presentation.reader.readerThemePresets
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/*
 * The novel half of the reader's settings sheet, in tsundoku's look: the rows a reader adjusts while
 * looking at the page. Every one is also on Settings -> Novel reader, and both write the same values.
 */

private const val TENTHS = 10f

/** Font, size, alignment, spacing and margins. */
@Composable
internal fun ColumnScope.NovelReadingPage(pages: ReaderSettingsPages.Novel) {
    val preferences = pages.preferences

    FontRow(preferences.readerFontFamily(), pages.installedFonts)

    val textAlign by preferences.readerTextAlign().collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical / 4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.strings.pref_novel_text_align),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Triple("left", ReikaiIcons.FormatAlignLeft, MR.strings.pref_novel_text_align_left),
                Triple("center", ReikaiIcons.FormatAlignCenter, MR.strings.pref_novel_text_align_center),
                Triple("justify", ReikaiIcons.FormatAlignJustify, MR.strings.pref_novel_text_align_justify),
                Triple("right", ReikaiIcons.FormatAlignRight, MR.strings.pref_novel_text_align_right),
            ).forEach { (value, icon, labelRes) ->
                FilledIconToggleButton(
                    checked = textAlign == value,
                    onCheckedChange = { preferences.readerTextAlign().set(value) },
                ) {
                    Icon(icon, contentDescription = stringResource(labelRes))
                }
            }
        }
    }

    val fontSize by preferences.readerFontSize().collectAsState()
    StepperItem(
        label = stringResource(MR.strings.pref_reader_text_size),
        value = fontSize,
        onChange = pages.textSettings::setFontSize,
        valueRange = 12..32,
        defaultValue = preferences.readerFontSize().defaultValue(),
    )
    TenthsStepper(preferences.readerLineSpacing(), MR.strings.pref_novel_line_spacing, 10..25, "%.1fx")
    TenthsStepper(preferences.readerParagraphIndent(), MR.strings.pref_paragraph_indent, 0..50, "%.1fem")
    TenthsStepper(preferences.readerParagraphSpacing(), MR.strings.pref_paragraph_spacing, 0..40, "%.1fem")
    MarginStepper(preferences.readerMarginTop(), MR.strings.pref_margin_top)
    MarginStepper(preferences.readerMarginBottom(), MR.strings.pref_margin_bottom)
    MarginStepper(preferences.readerMarginLeft(), MR.strings.pref_margin_left)
    MarginStepper(preferences.readerMarginRight(), MR.strings.pref_margin_right)
}

/** The page's colours and keeping the screen awake. */
@Composable
internal fun ColumnScope.NovelAppearancePage(pages: ReaderSettingsPages.Novel) {
    val preferences = pages.preferences
    val followSystem by preferences.readerFollowSystemTheme().collectAsState()
    val background by preferences.readerBackgroundColor().collectAsState()

    HeadingItem(MR.strings.pref_category_theme)
    // A radio, not a checkbox: following the system is left by picking a swatch, never by unticking it.
    RadioItem(
        label = stringResource(MR.strings.pref_novel_theme_follow_system),
        selected = followSystem,
        onClick = pages.textSettings::followSystemTheme,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        readerThemePresets.forEach { preset ->
            PresetSwatch(preset, selected = !followSystem && background.equals(preset.background, ignoreCase = true)) {
                pages.textSettings.setThemeColors(preset.background, preset.textColor)
            }
        }
    }

    CheckboxItem(label = stringResource(MR.strings.pref_keep_screen_on), pref = preferences.readerKeepScreenOn())
}

/** Auto-scroll, taps, swipes and the volume keys. */
@Composable
internal fun ColumnScope.NovelControlsPage(preferences: NovelPreferences) {
    val autoScroll by preferences.readerAutoScroll().collectAsState()
    CheckboxItem(label = stringResource(MR.strings.pref_auto_scroll), pref = preferences.readerAutoScroll())
    if (autoScroll) {
        val speedPref = preferences.readerAutoScrollSpeed()
        val speed by speedPref.collectAsState()
        SliderItem(
            value = (speed * TENTHS).roundToInt(),
            valueRange = 2..40,
            label = stringResource(MR.strings.pref_auto_scroll_speed),
            valueString = "%.1fx".format(speed),
            onChange = { speedPref.set(it / TENTHS) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
    CheckboxItem(label = stringResource(MR.strings.pref_tap_to_scroll), pref = preferences.readerTapToScroll())
    CheckboxItem(
        label = stringResource(MR.strings.pref_swipe_between_chapters),
        pref = preferences.readerSwipeGestures(),
    )

    val volumeKeys by preferences.readerUseVolumeButtons().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_volume_keys),
        pref = preferences.readerUseVolumeButtons(),
    )
    if (volumeKeys) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
            pref = preferences.readerVolumeButtonsInverted(),
        )
        val fractionPref = preferences.readerVolumeButtonsFraction()
        val fraction by fractionPref.collectAsState()
        val percent = (fraction * 100).roundToInt()
        SliderItem(
            value = percent,
            valueRange = 25..100,
            label = stringResource(MR.strings.pref_volume_keys_scroll_amount),
            valueString = "$percent%",
            onChange = { fractionPref.set(it / 100f) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/** A setting stored as a float and stepped in tenths, shown through [format]. */
@Composable
private fun TenthsStepper(pref: Preference<Float>, labelRes: StringResource, tenths: IntRange, format: String) {
    val value by pref.collectAsState()
    StepperItem(
        label = stringResource(labelRes),
        value = (value * TENTHS).roundToInt(),
        onChange = { pref.set(it / TENTHS) },
        valueRange = tenths,
        defaultValue = (pref.defaultValue() * TENTHS).roundToInt(),
        valueString = format.format(value),
    )
}

@Composable
private fun MarginStepper(pref: Preference<Int>, labelRes: StringResource) {
    val value by pref.collectAsState()
    StepperItem(
        label = stringResource(labelRes),
        value = value,
        onChange = pref::set,
        valueRange = 0..64,
        step = 2,
        defaultValue = pref.defaultValue(),
        valueString = "${value}dp",
    )
}

/** The font, named as the picker names it, and a list of every font to choose from. */
@Composable
private fun FontRow(pref: Preference<String>, installedFonts: suspend () -> List<NovelFont>) {
    val family by pref.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(MR.strings.pref_novel_font_default)
    val builtIn = remember { readerFonts.take(1) + readerGenericFonts + readerFonts.drop(1) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.strings.pref_novel_font),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = fontLabel(family, builtIn, defaultLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (picking) {
        val installed by produceState(emptyList<ReaderFont>()) {
            value = installedFonts().map { ReaderFont(it.fileName, it.displayName) }
        }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(MR.strings.pref_novel_font)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    (builtIn + installed).forEach { font ->
                        RadioItem(
                            label = if (font.family.isEmpty()) defaultLabel else font.name,
                            selected = font.family == family,
                            onClick = {
                                pref.set(font.family)
                                picking = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}

private fun fontLabel(family: String, builtIn: List<ReaderFont>, defaultLabel: String): String = when {
    family.isEmpty() -> defaultLabel
    else -> builtIn.firstOrNull { it.family == family }?.name ?: fontDisplayName(family)
}
