package reikai.presentation.reader.settings

import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import reikai.domain.novel.NovelChapterTitleFormat
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelTapLayout
import reikai.domain.novel.tts.TtsColorPreset
import reikai.domain.novel.tts.TtsHighlightColors
import reikai.domain.novel.tts.TtsHighlightStyle
import reikai.domain.novel.tts.baseLanguages
import reikai.domain.novel.tts.inLanguages
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
import reikai.presentation.reader.TtsOptions
import reikai.presentation.reader.readerColorOrNull
import reikai.presentation.reader.readerDarkPreset
import reikai.presentation.reader.readerFonts
import reikai.presentation.reader.readerGenericFonts
import reikai.presentation.reader.readerLightPreset
import reikai.presentation.reader.readerThemePresets
import reikai.presentation.reader.rememberTtsOptions
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
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The novel half of the reader's settings sheet, in tsundoku's look: the rows a reader adjusts while
 * looking at the page. Every one but the novel's own rotation is also on Settings -> Novel reader, and
 * both write the same values. Read aloud is a novel's tab alone.
 */

private const val TENTHS = 10f

/** Words the split threshold steps by, across its 20 to 2000 range. */
private const val AUTO_SPLIT_STEP = 10

/**
 * This novel's rotation, then how the text is drawn: the renderer, the font and its size, the paragraphs
 * and the margins around them.
 */
@Composable
internal fun ColumnScope.NovelReadingPage(pages: ReaderSettingsPages.Novel) {
    val preferences = pages.preferences

    HeadingItem(MR.strings.pref_category_for_this_series)
    val orientation by pages.orientation.collectAsState(null)
    EntryRotationRow(orientation) { pages.onChangeOrientation(it.flagValue) }

    HeadingItem(MR.strings.pref_category_text)
    val renderingModePref = preferences.readerRenderingMode()
    val renderingMode by renderingModePref.collectAsState()
    SettingsChipRow(MR.strings.pref_novel_rendering_mode) {
        NovelRenderingMode.entries.forEach {
            FilterChip(
                selected = renderingMode == it,
                onClick = { renderingModePref.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }
    FontRow(preferences.readerFontFamily(), pages.installedFonts)
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

    HeadingItem(MR.strings.pref_category_paragraphs)
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
    CheckboxItem(
        label = stringResource(MR.strings.pref_remove_extra_spacing),
        pref = preferences.readerRemoveExtraSpacing(),
    )
    CheckboxItem(label = stringResource(MR.strings.pref_bionic_reading), pref = preferences.readerBionicReading())

    HeadingItem(MR.strings.pref_category_margins)
    MarginStepper(preferences.readerMarginTop(), MR.strings.pref_margin_top)
    MarginStepper(preferences.readerMarginBottom(), MR.strings.pref_margin_bottom)
    MarginStepper(preferences.readerMarginLeft(), MR.strings.pref_margin_left)
    MarginStepper(preferences.readerMarginRight(), MR.strings.pref_margin_right)
}

/** The page's colours, what surrounds the text, and how a chapter's text is treated before it is shown. */
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

    HeadingItem(MR.strings.pref_category_page)
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
    CheckboxItem(
        label = stringResource(MR.strings.pref_show_reading_progress),
        pref = preferences.readerShowProgressPercentage(),
    )
    // The novel reader's own pair, which the manga tab shows for its reader in the same place.
    CheckboxItem(label = stringResource(MR.strings.pref_fullscreen), pref = preferences.readerFullscreen())
    val isFullscreen by preferences.readerFullscreen().collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(label = stringResource(MR.strings.pref_cutout_short), pref = preferences.readerDrawUnderCutout())
    }
    CheckboxItem(label = stringResource(MR.strings.pref_keep_screen_on), pref = preferences.readerKeepScreenOn())

    NovelChapterTextRows(preferences)
}

/** The rows of Settings -> Novel reader -> Chapter text that change what the page shows, in its order. */
@Composable
private fun ColumnScope.NovelChapterTextRows(preferences: NovelPreferences) {
    val renderingMode by preferences.readerRenderingMode().collectAsState()
    val autoSplit by preferences.readerAutoSplitText().collectAsState()
    val sourceCssPriority by preferences.readerSourceCssPriority().collectAsState()

    HeadingItem(MR.strings.pref_category_chapter_text)
    CheckboxItem(
        label = stringResource(MR.strings.pref_hide_chapter_title),
        pref = preferences.readerHideChapterTitle(),
    )
    CheckboxItem(label = stringResource(MR.strings.pref_force_lowercase), pref = preferences.readerForceLowercase())
    CheckboxItem(label = stringResource(MR.strings.pref_block_media), pref = preferences.readerBlockMedia())
    CheckboxItem(label = stringResource(MR.strings.pref_auto_split_text), pref = preferences.readerAutoSplitText())
    if (autoSplit) {
        val wordsPref = preferences.readerAutoSplitWordCount()
        val words by wordsPref.collectAsState()
        StepperItem(
            label = stringResource(MR.strings.pref_auto_split_word_count),
            value = words,
            onChange = wordsPref::set,
            valueRange = NovelTextRanges.autoSplitWords,
            step = AUTO_SPLIT_STEP,
            defaultValue = wordsPref.defaultValue(),
        )
    }
    // Only a WebView page has a stylesheet and fonts of the chapter's own to keep.
    if (renderingMode != NovelRenderingMode.WEBVIEW) return
    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_embedded_css),
        pref = preferences.readerKeepEmbeddedCss(),
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_source_css_priority),
        pref = preferences.readerSourceCssPriority(),
    )
    // Under chapter styling that wins, the reader's own font overrides are not applied at all.
    if (!sourceCssPriority) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_use_original_fonts),
            pref = preferences.readerUseOriginalFonts(),
        )
    }
}

/** Scrolling on its own, taps and swipes, and the volume keys. */
@Composable
internal fun ColumnScope.NovelControlsPage(preferences: NovelPreferences) {
    HeadingItem(MR.strings.pref_category_scrolling)
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

    val seamless by preferences.readerSeamlessChapters().collectAsState()
    if (seamless) {
        val autoLoadPref = preferences.readerAutoLoadNextAt()
        val autoLoad by autoLoadPref.collectAsState()
        SliderItem(
            value = autoLoad,
            valueRange = 50..100,
            label = stringResource(MR.strings.pref_novel_auto_load_next_at),
            valueString = "$autoLoad%",
            onChange = autoLoadPref::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    HeadingItem(MR.strings.pref_category_gestures)
    NovelTapZonesRows(preferences)
    CheckboxItem(
        label = stringResource(MR.strings.pref_swipe_between_chapters),
        pref = preferences.readerSwipeGestures(),
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_novel_text_selectable),
        pref = preferences.readerTextSelectable(),
    )

    HeadingItem(MR.strings.pref_reader_navigation)
    val showNavigator by preferences.readerShowNavigator().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_show_progress_navigator),
        pref = preferences.readerShowNavigator(),
    )
    if (showNavigator) {
        CheckboxItem(label = stringResource(MR.strings.pref_novel_use_rail), pref = preferences.readerUseRail())
    }
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

/** Read aloud's engine, voice and mark, as Settings -> Novel reader sets them. */
@Composable
internal fun ColumnScope.NovelReadAloudPage(preferences: NovelPreferences) {
    val context = LocalContext.current
    val engine by preferences.readerTtsEngine().collectAsState()
    val options by rememberTtsOptions(context, engine)
    HeadingItem(MR.strings.pref_tts_voice)
    EngineRow(preferences, options)
    VoiceLanguagesRow(preferences, options)
    VoiceRow(preferences, options)
    TenthsStepper(preferences.readerTtsRate(), MR.strings.pref_tts_rate, tenths = 1..30, format = "%.1fx")
    TenthsStepper(preferences.readerTtsPitch(), MR.strings.pref_tts_pitch, tenths = 1..20, format = "%.1f")

    HeadingItem(MR.strings.pref_category_playback)
    CheckboxItem(
        label = stringResource(MR.strings.pref_tts_auto_page_advance),
        pref = preferences.readerTtsAutoPageAdvance(),
    )

    val keepInView by preferences.readerTtsKeepInView().collectAsState()
    CheckboxItem(label = stringResource(MR.strings.pref_tts_keep_in_view), pref = preferences.readerTtsKeepInView())
    if (keepInView) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_tts_scroll_to_top),
            pref = preferences.readerTtsScrollToTop(),
        )
    }

    HeadingItem(MR.strings.pref_category_highlight)
    val highlight by preferences.readerTtsHighlight().collectAsState()
    CheckboxItem(label = stringResource(MR.strings.pref_tts_highlight), pref = preferences.readerTtsHighlight())
    if (!highlight) return
    CheckboxItem(
        label = stringResource(MR.strings.pref_tts_highlight_sentence),
        pref = preferences.readerTtsHighlightSentence(),
    )
    val stylePref = preferences.readerTtsHighlightStyle()
    val style by stylePref.collectAsState()
    SettingsChipRow(MR.strings.pref_tts_highlight_style) {
        TtsHighlightStyle.entries.forEach {
            FilterChip(
                selected = style == it,
                onClick = { stylePref.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }
    TtsColorRow(
        preferences.readerTtsHighlightColor(),
        TtsHighlightColors.highlight,
        MR.strings.pref_tts_highlight_color,
    )
    // Underline and outline leave the text's own colour alone.
    if (style == TtsHighlightStyle.BACKGROUND) {
        TtsColorRow(
            preferences.readerTtsHighlightTextColor(),
            TtsHighlightColors.text,
            MR.strings.pref_tts_highlight_text_color,
        )
    }
}

/** The engine, only offered when more than one is installed. A new engine stops playback. */
@Composable
private fun EngineRow(preferences: NovelPreferences, options: TtsOptions) {
    if (options.engines.size <= 1) return
    val enginePref = preferences.readerTtsEngine()
    val engine by enginePref.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(MR.strings.label_default)
    PickerRow(
        labelRes = MR.strings.pref_tts_engine,
        value = if (engine.isEmpty()) {
            defaultLabel
        } else {
            options.engines.firstOrNull { it.packageName == engine }?.label ?: engine
        },
        onClick = { picking = true },
    )
    if (picking) {
        ListPickerDialog(MR.strings.pref_tts_engine, onDismiss = { picking = false }) {
            (listOf("" to defaultLabel) + options.engines.map { it.packageName to it.label }).forEach { (name, label) ->
                RadioItem(label = label, selected = name == engine) {
                    // A voice belongs to the engine that offers it, so one kept across a switch never applies.
                    if (name != engine) preferences.readerTtsVoice().set("")
                    enginePref.set(name)
                    picking = false
                }
            }
        }
    }
}

/** Narrows the voice list to some languages, only offered when the voices span more than one. */
@Composable
private fun VoiceLanguagesRow(preferences: NovelPreferences, options: TtsOptions) {
    val languagesPref = preferences.readerTtsLanguages()
    val selected by languagesPref.collectAsState()
    val languages = remember(options.voices) {
        options.voices.baseLanguages()
            .map { code -> code to Locale.forLanguageTag(code).displayLanguage.ifBlank { code } }
            .sortedBy { it.second }
    }
    if (languages.size <= 1) return
    var picking by remember { mutableStateOf(false) }
    PickerRow(
        labelRes = MR.strings.pref_tts_languages,
        value = languages.filter { it.first in selected }.joinToString { it.second }
            .ifEmpty { stringResource(MR.strings.all) },
        onClick = { picking = true },
    )
    if (picking) {
        ListPickerDialog(MR.strings.pref_tts_languages, onDismiss = { picking = false }) {
            languages.forEach { (code, name) ->
                CheckboxItem(label = name, checked = code in selected) {
                    languagesPref.set(if (code in selected) selected - code else selected + code)
                }
            }
        }
    }
}

/** The voice, named as the engine names it, and a list of the voices in the languages picked above. */
@Composable
private fun VoiceRow(preferences: NovelPreferences, options: TtsOptions) {
    val voicePref = preferences.readerTtsVoice()
    val voice by voicePref.collectAsState()
    val languages by preferences.readerTtsLanguages().collectAsState()
    var picking by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(MR.strings.label_default)
    PickerRow(
        labelRes = MR.strings.pref_tts_voice,
        // Looked up in every voice: one picked before the language filter changed still plays.
        value = if (voice.isEmpty()) {
            defaultLabel
        } else {
            options.voices.firstOrNull { it.name == voice }?.displayName ?: voice
        },
        onClick = { picking = true },
    )
    if (picking) {
        val shown = remember(options.voices, languages) { options.voices.inLanguages(languages) }
        ListPickerDialog(MR.strings.pref_tts_voice, onDismiss = { picking = false }) {
            RadioItem(label = defaultLabel, selected = voice.isEmpty()) {
                voicePref.set("")
                picking = false
            }
            shown.forEach {
                RadioItem(label = it.displayName, selected = it.name == voice) {
                    voicePref.set(it.name)
                    picking = false
                }
            }
        }
    }
}

/** A setting's name and its current value, opening a picker on a tap. */
@Composable
private fun PickerRow(labelRes: StringResource, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** A scrolling list of choices under [titleRes], closed by its button or by picking one. */
@Composable
private fun ListPickerDialog(
    titleRes: StringResource,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) }
        },
    )
}

/** The named colours as chips, then Custom, which opens the picker and is selected for any other colour. */
@Composable
private fun ColumnScope.TtsColorRow(pref: Preference<Int>, presets: List<TtsColorPreset>, labelRes: StringResource) {
    val color by pref.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val label = stringResource(labelRes)
    SettingsChipRow(labelRes) {
        presets.forEach {
            FilterChip(
                selected = color == it.argb,
                onClick = { pref.set(it.argb) },
                label = { Text(stringResource(it.nameRes)) },
            )
        }
        FilterChip(
            selected = presets.none { it.argb == color },
            onClick = { picking = true },
            label = { Text(stringResource(MR.strings.color_custom)) },
        )
    }
    if (picking) {
        ColorPickerDialog(
            title = label,
            initialColor = color,
            onDismiss = { picking = false },
            onConfirm = {
                pref.set(it)
                picking = false
            },
        )
    }
}

private fun fontLabel(family: String, builtIn: List<ReaderFont>, defaultLabel: String): String = when {
    family.isEmpty() -> defaultLabel
    else -> builtIn.firstOrNull { it.family == family }?.name ?: fontDisplayName(family)
}
