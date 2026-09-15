package reikai.presentation.reader.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import reikai.domain.novel.NovelChapterTitleFormat
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelTapLayout
import reikai.novel.font.NovelFont
import reikai.novel.font.fontDisplayName
import reikai.presentation.components.ColorPickerDialog
import reikai.presentation.components.StepperItem
import reikai.presentation.components.toHexRgb
import reikai.presentation.icons.FormatAlignCenter
import reikai.presentation.icons.FormatAlignJustify
import reikai.presentation.icons.FormatAlignLeft
import reikai.presentation.icons.FormatAlignRight
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.reader.NovelTapZones
import reikai.presentation.reader.NovelTextRanges
import reikai.presentation.reader.PresetSwatch
import reikai.presentation.reader.ReaderFont
import reikai.presentation.reader.ReaderThemePreset
import reikai.presentation.reader.readerColorOrNull
import reikai.presentation.reader.readerDarkPreset
import reikai.presentation.reader.readerFonts
import reikai.presentation.reader.readerGenericFonts
import reikai.presentation.reader.readerLightPreset
import reikai.presentation.reader.readerThemePresets
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/*
 * The novel half of the reader's settings sheet, in tsundoku's look: the rows a reader adjusts while
 * looking at the page. Every one but the novel's own rotation is also on Settings -> Novel reader, and
 * both write the same values.
 */

private const val TENTHS = 10f

/** This novel's rotation, then font, size, alignment, spacing, margins and how the text is treated. */
@Composable
internal fun ColumnScope.NovelReadingPage(pages: ReaderSettingsPages.Novel) {
    val preferences = pages.preferences

    HeadingItem(MR.strings.pref_category_for_this_series)
    val orientation by pages.orientation.collectAsState(null)
    EntryRotationRow(orientation) { pages.onChangeOrientation(it.flagValue) }

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
        valueRange = NovelTextRanges.fontSize,
        defaultValue = preferences.readerFontSize().defaultValue(),
    )
    TenthsStepper(
        preferences.readerLineSpacing(),
        MR.strings.pref_novel_line_spacing,
        NovelTextRanges.lineHeightTenths,
        "%.1fx",
    )
    TenthsStepper(
        preferences.readerParagraphIndent(),
        MR.strings.pref_paragraph_indent,
        NovelTextRanges.paragraphIndentTenths,
        "%.1fem",
    )
    TenthsStepper(
        preferences.readerParagraphSpacing(),
        MR.strings.pref_paragraph_spacing,
        NovelTextRanges.paragraphSpacingTenths,
        "%.1fem",
    )
    MarginStepper(preferences.readerMarginTop(), MR.strings.pref_margin_top)
    MarginStepper(preferences.readerMarginBottom(), MR.strings.pref_margin_bottom)
    MarginStepper(preferences.readerMarginLeft(), MR.strings.pref_margin_left)
    MarginStepper(preferences.readerMarginRight(), MR.strings.pref_margin_right)
    CheckboxItem(label = stringResource(MR.strings.pref_bionic_reading), pref = preferences.readerBionicReading())
    CheckboxItem(
        label = stringResource(MR.strings.pref_remove_extra_spacing),
        pref = preferences.readerRemoveExtraSpacing(),
    )
}

/** The page's colours and keeping the screen awake. */
@Composable
internal fun ColumnScope.NovelAppearancePage(pages: ReaderSettingsPages.Novel) {
    val preferences = pages.preferences
    val followSystem by preferences.readerFollowSystemTheme().collectAsState()
    val background by preferences.readerBackgroundColor().collectAsState()
    val text by preferences.readerTextColor().collectAsState()
    // What the page shows, which under Follow system is the preset it resolves to, not what is stored.
    val shown = when {
        !followSystem -> ReaderThemePreset("", background, text)
        isSystemInDarkTheme() -> readerDarkPreset
        else -> readerLightPreset
    }

    HeadingItem(MR.strings.pref_category_theme)
    // A radio, not a checkbox: following the system is left by picking a swatch, never by unticking it.
    RadioItem(
        label = stringResource(MR.strings.pref_novel_theme_follow_system),
        selected = followSystem,
        onClick = pages.textSettings::followSystemTheme,
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        readerThemePresets.forEach { preset ->
            PresetSwatch(preset, selected = !followSystem && background.equals(preset.background, ignoreCase = true)) {
                pages.textSettings.setThemeColors(preset.background, preset.textColor)
            }
        }
    }
    // Either colour picked by hand is the custom theme; the other keeps what the page shows now.
    PageColorRow(MR.strings.pref_novel_background_color, shown.background) {
        pages.textSettings.setThemeColors(it, shown.textColor)
    }
    PageColorRow(MR.strings.pref_novel_text_color, shown.textColor) {
        pages.textSettings.setThemeColors(shown.background, it)
    }

    val titleFormatPref = preferences.readerChapterTitleFormat()
    val titleFormat by titleFormatPref.collectAsState()
    SettingsChipRow(MR.strings.pref_novel_chapter_title_format) {
        NovelChapterTitleFormat.entries.forEach {
            FilterChip(
                selected = titleFormat == it,
                onClick = { titleFormatPref.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
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
    NovelTapZonesRows(preferences)
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

/** The tap layout, its inversion where it has something to invert, and the bottom layout's zone height. */
@Composable
private fun ColumnScope.NovelTapZonesRows(preferences: NovelPreferences) {
    val layoutPref = preferences.readerTapLayout()
    val invertPref = preferences.readerTapInvert()
    val layout by layoutPref.collectAsState()
    val invert by invertPref.collectAsState()

    SettingsChipRow(MR.strings.pref_viewer_nav) {
        NovelTapLayout.entries.forEach {
            FilterChip(
                selected = layout == it,
                onClick = { preferences.setReaderTapLayout(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }
    // Disabled has no zones to invert, and a layout with one inversion has no choice to offer.
    if (layout != NovelTapLayout.DISABLED && layout.invertModes.size > 1) {
        SettingsChipRow(MR.strings.pref_read_with_tapping_inverted) {
            layout.invertModes.forEach {
                FilterChip(
                    selected = it == invert,
                    onClick = { invertPref.set(it) },
                    label = { Text(stringResource(it.titleRes)) },
                )
            }
        }
    }
    if (layout == NovelTapLayout.BOTTOM) {
        val heightPref = preferences.readerTapBottomZoneHeight()
        val height by heightPref.collectAsState()
        SliderItem(
            value = height,
            valueRange = NovelTapZones.BOTTOM_ZONE_PERCENT,
            label = stringResource(MR.strings.pref_tap_bottom_zone_height),
            valueString = "$height%",
            onChange = heightPref::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/** One page colour with its swatch and hex, picked by hand; [onPick] gets six hex digits. */
@Composable
private fun PageColorRow(labelRes: StringResource, hex: String, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val label = stringResource(labelRes)
    val color =
        remember(hex) { readerColorOrNull(hex) ?: Color.Gray.toArgb() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = color.toHexRgb(), style = MaterialTheme.typography.bodyMedium)
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
    if (picking) {
        ColorPickerDialog(
            title = label,
            initialColor = color,
            onDismiss = { picking = false },
            onConfirm = {
                onPick(it.toHexRgb())
                picking = false
            },
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
        scale = TENTHS.toInt(),
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
        valueRange = NovelTextRanges.marginDp,
        step = 2,
        defaultValue = pref.defaultValue(),
        valueString = "${value}dp",
    )
}

/** The one choice that sets no font, so it says what the reader uses instead, as the fonts screen does. */
@Composable
private fun DefaultFontItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(MR.strings.pref_novel_font_default_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
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
                        val select = {
                            pref.set(font.family)
                            picking = false
                        }
                        if (font.family.isEmpty()) {
                            DefaultFontItem(defaultLabel, selected = family.isEmpty(), onClick = select)
                        } else {
                            RadioItem(label = font.name, selected = font.family == family, onClick = select)
                        }
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
