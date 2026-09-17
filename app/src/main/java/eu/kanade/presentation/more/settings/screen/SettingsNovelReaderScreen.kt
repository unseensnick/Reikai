package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.novel.NovelCodeSnippetsScreen
import eu.kanade.presentation.more.settings.screen.novel.NovelFontsScreen
import eu.kanade.presentation.more.settings.screen.novel.NovelRegexRulesScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import mihon.app.di.appGraph
import reikai.domain.novel.NovelChapterTitleFormat
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelTapLayout
import reikai.domain.novel.tts.TtsColorPreset
import reikai.domain.novel.tts.TtsHighlightColors
import reikai.domain.novel.tts.TtsHighlightStyle
import reikai.domain.novel.tts.baseLanguages
import reikai.domain.novel.tts.inLanguages
import reikai.novel.content.NovelSnippetKind
import reikai.novel.font.fontDisplayName
import reikai.presentation.components.ColorPickerDialog
import reikai.presentation.components.toHexRgb
import reikai.presentation.reader.NovelTapZones
import reikai.presentation.reader.NovelTextRanges
import reikai.presentation.reader.readerBottomButtonsPreference
import reikai.presentation.reader.readerFonts
import reikai.presentation.reader.readerGenericFonts
import reikai.presentation.reader.rememberTtsOptions
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale
import kotlin.math.roundToInt
import tachiyomi.core.common.preference.Preference as PreferenceStoreEntry

/**
 * Light-novel reader settings, a top-level Settings entry beside [SettingsMangaReaderScreen]. Settings
 * of the same name on the two screens are deliberately separate values; see that screen's note.
 *
 * Text size and the page colours stay in the reader's settings sheet, which previews them as you change
 * them.
 */
object SettingsNovelReaderScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_novel_reader

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val novelPref = remember { context.appGraph.novelPreferences }

        return listOf(
            getReadingGroup(novelPreferences = novelPref),
            getTextDisplayGroup(novelPreferences = novelPref),
            getChapterTextGroup(novelPreferences = novelPref),
            getNavigationGroup(novelPreferences = novelPref),
            getReadAloudGroup(novelPreferences = novelPref),
            getAccessibilityGroup(novelPreferences = novelPref),
        )
    }

    @Composable
    private fun getReadAloudGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val enginePref = novelPreferences.readerTtsEngine()
        val voicePref = novelPreferences.readerTtsVoice()
        val ratePref = novelPreferences.readerTtsRate()
        val pitchPref = novelPreferences.readerTtsPitch()
        val engine by enginePref.collectAsState()
        val selectedLanguages by novelPreferences.readerTtsLanguages().collectAsState()
        val rate by ratePref.collectAsState()
        val pitch by pitchPref.collectAsState()
        val highlight by novelPreferences.readerTtsHighlight().collectAsState()
        val keepInView by novelPreferences.readerTtsKeepInView().collectAsState()
        val highlightStyle by novelPreferences.readerTtsHighlightStyle().collectAsState()
        val options by rememberTtsOptions(context, engine)

        val defaultLabel = stringResource(MR.strings.label_default)
        val languages = remember(options.voices) {
            options.voices.baseLanguages()
                .map { code -> code to Locale.forLanguageTag(code).displayLanguage.ifBlank { code } }
                .sortedBy { it.second }
                .toMap()
        }
        val voiceNames = remember(options.voices) { options.voices.associate { it.name to it.displayName } }
        val shownVoices = remember(options.voices, selectedLanguages) {
            options.voices.inLanguages(selectedLanguages).associate { it.name to it.displayName }
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_read_aloud),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.ListPreference(
                    preference = enginePref,
                    entries = mapOf("" to defaultLabel) + options.engines.associate { it.packageName to it.label },
                    title = stringResource(MR.strings.pref_tts_engine),
                    subtitleProvider = { value, entries -> entries[value] ?: value },
                    // A voice belongs to the engine that offers it, so one kept across a switch never applies.
                    onValueChanged = {
                        if (it != engine) voicePref.set("")
                        true
                    },
                ).takeIf { options.engines.size > 1 },
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = novelPreferences.readerTtsLanguages(),
                    entries = languages,
                    title = stringResource(MR.strings.pref_tts_languages),
                    subtitleProvider = { values, entries ->
                        values.mapNotNull { entries[it] }.joinToString().ifEmpty { stringResource(MR.strings.all) }
                    },
                ).takeIf { languages.size > 1 },
                Preference.PreferenceItem.ListPreference(
                    preference = voicePref,
                    entries = mapOf("" to defaultLabel) + shownVoices,
                    title = stringResource(MR.strings.pref_tts_voice),
                    // Looked up in every voice, not the filtered ones: a voice picked before the filter
                    // changed still plays, so it should still read by its name.
                    subtitleProvider = { value, _ ->
                        if (value.isEmpty()) defaultLabel else voiceNames[value] ?: value
                    },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (rate * TENTHS).roundToInt(),
                    valueRange = 1..30,
                    title = stringResource(MR.strings.pref_tts_rate),
                    valueString = "%.1fx".format(rate),
                    onValueChanged = { ratePref.set(it / TENTHS) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (pitch * TENTHS).roundToInt(),
                    valueRange = 1..20,
                    title = stringResource(MR.strings.pref_tts_pitch),
                    valueString = "%.1f".format(pitch),
                    onValueChanged = { pitchPref.set(it / TENTHS) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerTtsAutoPageAdvance(),
                    title = stringResource(MR.strings.pref_tts_auto_page_advance),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerTtsKeepInView(),
                    title = stringResource(MR.strings.pref_tts_keep_in_view),
                ),
                // Where a paragraph kept in view is put, so it only means something while that is on.
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerTtsScrollToTop(),
                    title = stringResource(MR.strings.pref_tts_scroll_to_top),
                    subtitle = stringResource(MR.strings.pref_tts_scroll_to_top_summary),
                ).takeIf { keepInView },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerTtsHighlight(),
                    title = stringResource(MR.strings.pref_tts_highlight),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerTtsHighlightSentence(),
                    title = stringResource(MR.strings.pref_tts_highlight_sentence),
                    subtitle = stringResource(MR.strings.pref_tts_highlight_sentence_summary),
                ).takeIf { highlight },
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerTtsHighlightStyle(),
                    entries = TtsHighlightStyle.entries.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_tts_highlight_style),
                ).takeIf { highlight },
                colorRow(
                    preference = novelPreferences.readerTtsHighlightColor(),
                    presets = TtsHighlightColors.highlight,
                    titleRes = MR.strings.pref_tts_highlight_color,
                ).takeIf { highlight },
                // Underline and outline leave the text's own colour alone.
                colorRow(
                    preference = novelPreferences.readerTtsHighlightTextColor(),
                    presets = TtsHighlightColors.text,
                    titleRes = MR.strings.pref_tts_highlight_text_color,
                ).takeIf { highlight && highlightStyle == TtsHighlightStyle.BACKGROUND },
            ),
        )
    }

    /** The presets plus Custom, which opens the picker rather than storing itself. */
    @Composable
    private fun colorRow(
        preference: PreferenceStoreEntry<Int>,
        presets: List<TtsColorPreset>,
        titleRes: StringResource,
    ): Preference.PreferenceItem.ListPreference<Int> {
        val title = stringResource(titleRes)
        val customLabel = stringResource(MR.strings.color_custom)
        var picking by remember { mutableStateOf(false) }
        if (picking) {
            ColorPickerDialog(
                title = title,
                initialColor = preference.get(),
                onDismiss = { picking = false },
                onConfirm = {
                    preference.set(it)
                    picking = false
                },
            )
        }
        return Preference.PreferenceItem.ListPreference(
            preference = preference,
            entries = presets.associate { it.argb to stringResource(it.nameRes) } + (CUSTOM_COLOR to customLabel),
            title = title,
            subtitleProvider = { value, entries -> entries[value] ?: "$customLabel (${value.toHexRgb()})" },
            onValueChanged = {
                if (it == CUSTOM_COLOR) picking = true
                it != CUSTOM_COLOR
            },
        )
    }

    /**
     * How the page is set, applied by whichever renderer draws the chapter. Indent and paragraph
     * spacing are multiples of the text size, so they hold their proportions when it changes.
     * Novel-only by mechanism: a manga page is an image the source ships, so there is no text for
     * any of this to act on.
     */
    @Composable
    private fun getTextDisplayGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val indentPref = novelPreferences.readerParagraphIndent()
        val spacingPref = novelPreferences.readerParagraphSpacing()
        val lineSpacingPref = novelPreferences.readerLineSpacing()
        val indent by indentPref.collectAsState()
        val spacing by spacingPref.collectAsState()
        val lineSpacing by lineSpacingPref.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val fontFamily by novelPreferences.readerFontFamily().collectAsState()
        // Named from whichever list it came from, since the preference holds a key or a file name and
        // neither reads as the font it selects.
        val defaultFontLabel = stringResource(MR.strings.pref_novel_font_default)
        val fontLabel = remember(fontFamily, defaultFontLabel) {
            when {
                fontFamily.isEmpty() -> defaultFontLabel
                else -> (readerGenericFonts + readerFonts).firstOrNull { it.family == fontFamily }?.name
                    ?: fontDisplayName(fontFamily)
            }
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_text_display),
            preferenceItems = listOf(
                // Its own screen rather than a list dialog: it also imports, downloads and removes
                // fonts, and a second place to pick one would be a second answer to the same question.
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_novel_font),
                    subtitle = fontLabel,
                    onClick = { navigator.push(NovelFontsScreen()) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (lineSpacing * TENTHS).roundToInt(),
                    valueRange = NovelTextRanges.lineHeightTenths,
                    title = stringResource(MR.strings.pref_novel_line_spacing),
                    valueString = "%.1fx".format(lineSpacing),
                    onValueChanged = { lineSpacingPref.set(it / TENTHS) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerTextAlign(),
                    entries = mapOf(
                        "left" to stringResource(MR.strings.pref_novel_text_align_left),
                        "center" to stringResource(MR.strings.pref_novel_text_align_center),
                        "justify" to stringResource(MR.strings.pref_novel_text_align_justify),
                        "right" to stringResource(MR.strings.pref_novel_text_align_right),
                    ),
                    title = stringResource(MR.strings.pref_novel_text_align),
                ),
                marginRow(novelPreferences.readerMarginTop(), MR.strings.pref_margin_top),
                marginRow(novelPreferences.readerMarginBottom(), MR.strings.pref_margin_bottom),
                marginRow(novelPreferences.readerMarginLeft(), MR.strings.pref_margin_left),
                marginRow(novelPreferences.readerMarginRight(), MR.strings.pref_margin_right),
                Preference.PreferenceItem.SliderPreference(
                    value = (indent * TENTHS).roundToInt(),
                    valueRange = NovelTextRanges.paragraphIndentTenths,
                    title = stringResource(MR.strings.pref_paragraph_indent),
                    subtitle = stringResource(MR.strings.pref_paragraph_indent_summary),
                    valueString = "%.1fem".format(indent),
                    onValueChanged = { indentPref.set(it / TENTHS) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (spacing * TENTHS).roundToInt(),
                    valueRange = NovelTextRanges.paragraphSpacingTenths,
                    title = stringResource(MR.strings.pref_paragraph_spacing),
                    subtitle = stringResource(MR.strings.pref_paragraph_spacing_summary),
                    valueString = "%.1fem".format(spacing),
                    onValueChanged = { spacingPref.set(it / TENTHS) },
                ),
            ),
        )
    }

    /** One page margin, in dp. */
    @Composable
    private fun marginRow(
        preference: PreferenceStoreEntry<Int>,
        titleRes: StringResource,
    ): Preference.PreferenceItem.SliderPreference {
        val value by preference.collectAsState()
        return Preference.PreferenceItem.SliderPreference(
            value = value,
            valueRange = NovelTextRanges.marginDp,
            title = stringResource(titleRes),
            valueString = "${value}dp",
            onValueChanged = { preference.set(it) },
        )
    }

    /**
     * How a chapter's markup is processed before it is rendered, in the order the pipeline applies
     * them. The two embedded-markup rows only bind while a WebView renders the chapter; a text
     * renderer draws CSS and scripts as visible characters, so it strips them regardless.
     */
    @Composable
    private fun getChapterTextGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val navigator = LocalNavigator.currentOrThrow
        val renderingMode by novelPreferences.readerRenderingMode().collectAsState()
        val autoSplitEnabled by novelPreferences.readerAutoSplitText().collectAsState()
        val autoSplitWordCount by novelPreferences.readerAutoSplitWordCount().collectAsState()
        val sourceCssPriority by novelPreferences.readerSourceCssPriority().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_chapter_text),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerHideChapterTitle(),
                    title = stringResource(MR.strings.pref_hide_chapter_title),
                    subtitle = stringResource(MR.strings.pref_hide_chapter_title_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerForceLowercase(),
                    title = stringResource(MR.strings.pref_force_lowercase),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerBlockMedia(),
                    title = stringResource(MR.strings.pref_block_media),
                    subtitle = stringResource(MR.strings.pref_block_media_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerAutoSplitText(),
                    title = stringResource(MR.strings.pref_auto_split_text),
                    subtitle = stringResource(MR.strings.pref_auto_split_text_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = autoSplitWordCount,
                    valueRange = NovelTextRanges.autoSplitWords,
                    steps = 0,
                    title = stringResource(MR.strings.pref_auto_split_word_count),
                    onValueChanged = { novelPreferences.readerAutoSplitWordCount().set(it) },
                ).takeIf { autoSplitEnabled },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepEmbeddedCss(),
                    title = stringResource(MR.strings.pref_keep_embedded_css),
                    subtitle = stringResource(MR.strings.pref_keep_embedded_css_summary),
                ).takeIf { renderingMode != NovelRenderingMode.NATIVE },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepEmbeddedJs(),
                    title = stringResource(MR.strings.pref_keep_embedded_js),
                    subtitle = stringResource(MR.strings.pref_keep_embedded_js_summary),
                ).takeIf { renderingMode != NovelRenderingMode.NATIVE },
                // Only the WebView renderer honours these two.
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSourceCssPriority(),
                    title = stringResource(MR.strings.pref_source_css_priority),
                    subtitle = stringResource(MR.strings.pref_source_css_priority_summary),
                ).takeIf { renderingMode == NovelRenderingMode.WEBVIEW },
                // The font switch only edits the reader's own overrides, and a chapter whose styling
                // wins gets none of them, so under that it would do nothing.
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerUseOriginalFonts(),
                    title = stringResource(MR.strings.pref_use_original_fonts),
                    subtitle = stringResource(MR.strings.pref_use_original_fonts_summary),
                ).takeIf { renderingMode == NovelRenderingMode.WEBVIEW && !sourceCssPriority },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerShowRawHtml(),
                    title = stringResource(MR.strings.pref_novel_show_raw_html),
                    subtitle = stringResource(MR.strings.pref_novel_show_raw_html_summary),
                ),
                // Its own screen rather than a row: a rule is five fields plus a preview, and the
                // list has no useful upper bound.
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_novel_regex_rules),
                    subtitle = stringResource(MR.strings.pref_novel_regex_rules_summary),
                    onClick = { navigator.push(NovelRegexRulesScreen()) },
                ),
                // Only a WebView page has a stylesheet and a script to add them to.
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_novel_css_snippets),
                    subtitle = stringResource(MR.strings.pref_novel_css_snippets_summary),
                    onClick = { navigator.push(NovelCodeSnippetsScreen(NovelSnippetKind.CSS)) },
                ).takeIf { renderingMode == NovelRenderingMode.WEBVIEW },
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_novel_js_snippets),
                    subtitle = stringResource(MR.strings.pref_novel_js_snippets_summary),
                    onClick = { navigator.push(NovelCodeSnippetsScreen(NovelSnippetKind.JS)) },
                ).takeIf { renderingMode == NovelRenderingMode.WEBVIEW },
            ),
        )
    }

    @Composable
    private fun getReadingGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val renderingMode by novelPreferences.readerRenderingMode().collectAsState()
        val seamless by novelPreferences.readerSeamlessChapters().collectAsState()
        val autoScrollSpeedPref = novelPreferences.readerAutoScrollSpeed()
        val autoScrollSpeed by autoScrollSpeedPref.collectAsState()
        val autoScroll by novelPreferences.readerAutoScroll().collectAsState()
        val fullscreen by novelPreferences.readerFullscreen().collectAsState()
        val tapLayout by novelPreferences.readerTapLayout().collectAsState()
        val markReadPercentPref = novelPreferences.readerMarkReadPercent()
        val markReadPercent by markReadPercentPref.collectAsState()
        val bottomZoneHeightPref = novelPreferences.readerTapBottomZoneHeight()
        val bottomZoneHeight by bottomZoneHeightPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reading),
            preferenceItems = listOfNotNull(
                // Read when a novel is opened, not while one is on screen, so a change applies to the
                // next chapter opened rather than the session in progress.
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerRenderingMode(),
                    entries = NovelRenderingMode.entries.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_novel_rendering_mode),
                ),
                // Only the native renderer gives up link taps for it, so only it carries the warning.
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerTextSelectable(),
                    title = stringResource(MR.strings.pref_novel_text_selectable),
                    subtitle = stringResource(MR.strings.pref_novel_text_selectable_summary)
                        .takeIf { renderingMode == NovelRenderingMode.NATIVE },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSeamlessChapters(),
                    title = stringResource(MR.strings.pref_novel_seamless_chapters),
                    subtitle = stringResource(MR.strings.pref_novel_seamless_chapters_summary),
                ),
                // Only a window has a marker between two chapters to hide, and the end-of-novel marker
                // shows whatever this says.
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerAlwaysShowChapterTransition(),
                    title = stringResource(MR.strings.pref_always_show_chapter_transition),
                ).takeIf { seamless },
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerChapterTitleFormat(),
                    entries = NovelChapterTitleFormat.entries.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_novel_chapter_title_format),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerDefaultOrientation(),
                    entries = ReaderOrientation.entries
                        .filter { it != ReaderOrientation.DEFAULT && it != ReaderOrientation.REVERSE_PORTRAIT }
                        .associate { it.flagValue to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_rotation_type),
                    subtitle = "%s",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerFullscreen(),
                    title = stringResource(MR.strings.pref_fullscreen),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerDrawUnderCutout(),
                    title = stringResource(MR.strings.pref_cutout_short),
                    enabled = LocalView.current.hasDisplayCutout() && fullscreen,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerTapLayout(),
                    entries = NovelTapLayout.entries.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_viewer_nav),
                    // Stored through the helper, which also drops an inversion the new layout cannot draw.
                    onValueChanged = {
                        novelPreferences.setReaderTapLayout(it)
                        false
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerTapInvert(),
                    entries = tapLayout.invertModes.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                ).takeIf { tapLayout != NovelTapLayout.DISABLED && tapLayout.invertModes.size > 1 },
                Preference.PreferenceItem.SliderPreference(
                    value = bottomZoneHeight,
                    valueRange = NovelTapZones.BOTTOM_ZONE_PERCENT,
                    title = stringResource(MR.strings.pref_tap_bottom_zone_height),
                    valueString = "$bottomZoneHeight%",
                    onValueChanged = { bottomZoneHeightPref.set(it) },
                ).takeIf { tapLayout == NovelTapLayout.BOTTOM },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSwipeGestures(),
                    title = stringResource(MR.strings.pref_swipe_between_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSkipRead(),
                    title = stringResource(MR.strings.pref_skip_read_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSkipFiltered(),
                    title = stringResource(MR.strings.pref_skip_filtered_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSkipDuplicateChapters(),
                    title = stringResource(MR.strings.pref_skip_dupe_chapters),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = markReadPercent,
                    valueRange = 50..100,
                    title = stringResource(MR.strings.pref_novel_mark_read_percent),
                    valueString = "$markReadPercent%",
                    onValueChanged = { markReadPercentPref.set(it) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerMarkReadOnSkip(),
                    title = stringResource(MR.strings.pref_mark_read_on_skip),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerAutoScroll(),
                    title = stringResource(MR.strings.pref_auto_scroll),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (autoScrollSpeed * TENTHS).roundToInt(),
                    valueRange = 2..40,
                    title = stringResource(MR.strings.pref_auto_scroll_speed),
                    valueString = "%.1fx".format(autoScrollSpeed),
                    onValueChanged = { autoScrollSpeedPref.set(it / TENTHS) },
                ).takeIf { autoScroll },
                readerBottomButtonsPreference(ReaderBottomButton.BarPreferences.novel(novelPreferences)),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerPreserveReadingPosition(),
                    title = stringResource(MR.strings.pref_preserve_reading_position),
                    subtitle = stringResource(MR.strings.pref_preserve_reading_position_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getNavigationGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val useVolumeButtonsPref = novelPreferences.readerUseVolumeButtons()
        val useVolumeButtons by useVolumeButtonsPref.collectAsState()
        val volumeButtonsFractionPref = novelPreferences.readerVolumeButtonsFraction()
        val volumeButtonsFraction by volumeButtonsFractionPref.collectAsState()
        val volumeButtonsPercent = (volumeButtonsFraction * 100).roundToInt()
        // Gated on the rail alone, unlike the manga screen's pair: a novel has no reading mode to pick it.
        val railHeightPref = novelPreferences.readerRailHeight()
        val railHeight by railHeightPref.collectAsState()
        val useRail by novelPreferences.readerUseRail().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_navigation),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.SwitchPreference(
                    preference = useVolumeButtonsPref,
                    title = stringResource(MR.strings.pref_read_with_volume_keys),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerVolumeButtonsInverted(),
                    title = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
                    enabled = useVolumeButtons,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = volumeButtonsPercent,
                    valueRange = 25..100,
                    title = stringResource(MR.strings.pref_volume_keys_scroll_amount),
                    valueString = "$volumeButtonsPercent%",
                    enabled = useVolumeButtons,
                    onValueChanged = { volumeButtonsFractionPref.set(it / 100f) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerUseRail(),
                    title = stringResource(MR.strings.pref_novel_use_rail),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerRailOnLeft(),
                    title = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
                ).takeIf { useRail },
                Preference.PreferenceItem.SliderPreference(
                    value = railHeight,
                    valueRange = 65..100,
                    steps = 6,
                    title = stringResource(MR.strings.pref_vertical_navigator_height),
                    onValueChanged = { railHeightPref.set(it) },
                ).takeIf { useRail },
            ),
        )
    }

    @Composable
    private fun getAccessibilityGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_accessibility),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepScreenOn(),
                    title = stringResource(MR.strings.pref_keep_screen_on),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerShowProgressPercentage(),
                    title = stringResource(MR.strings.pref_show_reading_progress),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerBionicReading(),
                    title = stringResource(MR.strings.pref_bionic_reading),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerRemoveExtraSpacing(),
                    title = stringResource(MR.strings.pref_remove_extra_spacing),
                ),
            ),
        )
}

/** Custom's key in a colour list. Fully transparent, so no colour the picker writes can equal it. */
private const val CUSTOM_COLOR = 0

/** The slider rows are integers, so an em value rides across as tenths of one. */
private const val TENTHS = 10f
