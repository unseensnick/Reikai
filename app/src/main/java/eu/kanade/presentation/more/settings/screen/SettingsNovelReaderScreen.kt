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
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderHideThreshold
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import mihon.app.di.appGraph
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelTapLayout
import reikai.domain.novel.NovelTextAlign
import reikai.domain.novel.tts.TtsColorPreset
import reikai.domain.novel.tts.TtsHighlightColors
import reikai.domain.novel.tts.TtsHighlightStyle
import reikai.domain.reader.ChapterTitleFormat
import reikai.novel.content.NovelSnippetKind
import reikai.presentation.components.ColorPickerDialog
import reikai.presentation.components.toHexRgb
import reikai.presentation.reader.NovelTapZones
import reikai.presentation.reader.NovelTextRanges
import reikai.presentation.reader.ReaderRanges
import reikai.presentation.reader.TtsOptions
import reikai.presentation.reader.autoScrollSpeedPreference
import reikai.presentation.reader.readerBottomButtonsPreference
import reikai.presentation.reader.readerFontLabel
import reikai.presentation.reader.rememberTtsOptions
import reikai.presentation.reader.tenthsLabel
import reikai.presentation.reader.volumeKeyScrollPreference
import reikai.util.scaled
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
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
        val ratePref = novelPreferences.readerTtsRate().scaled(ReaderRanges.TENTHS)
        val pitchPref = novelPreferences.readerTtsPitch().scaled(ReaderRanges.TENTHS)
        val engine by enginePref.collectAsState()
        val selectedLanguages by novelPreferences.readerTtsLanguages().collectAsState()
        val highlight by novelPreferences.readerTtsHighlight().collectAsState()
        val keepInView by novelPreferences.readerTtsKeepInView().collectAsState()
        val highlightStyle by novelPreferences.readerTtsHighlightStyle().collectAsState()
        // Search composes every screen for each query, and binding an engine there only to name three rows
        // would start one per keystroke, so it indexes them without one.
        val indexing = LocalSettingsIndexing.current
        val options = if (indexing) TtsOptions() else rememberTtsOptions(context, engine).value

        val defaultLabel = stringResource(MR.strings.label_default)
        val languages = remember(options) { options.languageNames() }
        val shownVoices = remember(options, selectedLanguages, defaultLabel) {
            options.voiceEntries(selectedLanguages, defaultLabel)
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_read_aloud),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.ListPreference(
                    preference = enginePref,
                    entries = options.engineEntries(defaultLabel),
                    title = stringResource(MR.strings.pref_tts_engine),
                    subtitleProvider = { value, _ -> options.engineLabel(value, defaultLabel) },
                    // Stored through the helper, which also clears a voice the new engine does not offer.
                    onValueChanged = {
                        novelPreferences.setReaderTtsEngine(it)
                        false
                    },
                ).takeIf { indexing || options.engines.size > 1 },
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = novelPreferences.readerTtsLanguages(),
                    entries = languages,
                    title = stringResource(MR.strings.pref_tts_languages),
                    subtitleProvider = { values, _ ->
                        options.languagesLabel(values).ifEmpty { stringResource(MR.strings.all) }
                    },
                ).takeIf { indexing || languages.size > 1 },
                Preference.PreferenceItem.ListPreference(
                    preference = voicePref,
                    entries = shownVoices,
                    title = stringResource(MR.strings.pref_tts_voice),
                    subtitleProvider = { value, _ -> options.voiceLabel(value, defaultLabel) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    preference = ratePref,
                    valueRange = NovelTextRanges.readAloudRateTenths,
                    title = stringResource(MR.strings.pref_tts_rate),
                    valueText = { tenthsLabel(it, "%.1fx") },
                ),
                Preference.PreferenceItem.SliderPreference(
                    preference = pitchPref,
                    valueRange = NovelTextRanges.readAloudPitchTenths,
                    title = stringResource(MR.strings.pref_tts_pitch),
                    valueText = { tenthsLabel(it, "%.1f") },
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
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val fontFamily by novelPreferences.readerFontFamily().collectAsState()
        // Named from whichever list it came from, since the preference holds a key or a file name and
        // neither reads as the font it selects.
        val defaultFontLabel = stringResource(MR.strings.pref_novel_font_default)
        val fontLabel = remember(fontFamily, defaultFontLabel) { readerFontLabel(fontFamily, defaultFontLabel) }

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
                    preference = novelPreferences.readerLineSpacing().scaled(ReaderRanges.TENTHS),
                    valueRange = NovelTextRanges.lineHeightTenths,
                    title = stringResource(MR.strings.pref_novel_line_spacing),
                    valueText = { tenthsLabel(it, "%.1fx") },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerTextAlign(),
                    entries = NovelTextAlign.entries.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_novel_text_align),
                ),
                marginRow(novelPreferences.readerMarginTop(), MR.strings.pref_margin_top),
                marginRow(novelPreferences.readerMarginBottom(), MR.strings.pref_margin_bottom),
                marginRow(novelPreferences.readerMarginLeft(), MR.strings.pref_margin_left),
                marginRow(novelPreferences.readerMarginRight(), MR.strings.pref_margin_right),
                Preference.PreferenceItem.SliderPreference(
                    preference = novelPreferences.readerParagraphIndent().scaled(ReaderRanges.TENTHS),
                    valueRange = NovelTextRanges.paragraphIndentTenths,
                    title = stringResource(MR.strings.pref_paragraph_indent),
                    subtitle = stringResource(MR.strings.pref_paragraph_indent_summary),
                    valueText = { tenthsLabel(it, "%.1fem") },
                ),
                Preference.PreferenceItem.SliderPreference(
                    preference = novelPreferences.readerParagraphSpacing().scaled(ReaderRanges.TENTHS),
                    valueRange = NovelTextRanges.paragraphSpacingTenths,
                    title = stringResource(MR.strings.pref_paragraph_spacing),
                    subtitle = stringResource(MR.strings.pref_paragraph_spacing_summary),
                    valueText = { tenthsLabel(it, "%.1fem") },
                ),
            ),
        )
    }

    /** One page margin, in dp. */
    @Composable
    private fun marginRow(
        preference: PreferenceStoreEntry<Int>,
        titleRes: StringResource,
    ) = Preference.PreferenceItem.SliderPreference(
        preference = preference,
        valueRange = NovelTextRanges.marginDp,
        title = stringResource(titleRes),
        valueText = { "${it}dp" },
    )

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
                    preference = novelPreferences.readerAutoSplitWordCount(),
                    valueRange = NovelTextRanges.autoSplitWords,
                    steps = 0,
                    title = stringResource(MR.strings.pref_auto_split_word_count),
                ).takeIf { autoSplitEnabled },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepEmbeddedCss(),
                    title = stringResource(MR.strings.pref_keep_embedded_css),
                    subtitle = stringResource(MR.strings.pref_keep_embedded_css_summary),
                ).takeIf { renderingMode.rendersMarkup },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepEmbeddedJs(),
                    title = stringResource(MR.strings.pref_keep_embedded_js),
                    subtitle = stringResource(MR.strings.pref_keep_embedded_js_summary),
                ).takeIf { renderingMode.rendersMarkup },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSourceCssPriority(),
                    title = stringResource(MR.strings.pref_source_css_priority),
                    subtitle = stringResource(MR.strings.pref_source_css_priority_summary),
                ).takeIf { renderingMode.rendersMarkup },
                // The font switch only edits the reader's own overrides, and a chapter whose styling
                // wins gets none of them, so under that it would do nothing.
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerUseOriginalFonts(),
                    title = stringResource(MR.strings.pref_use_original_fonts),
                    subtitle = stringResource(MR.strings.pref_use_original_fonts_summary),
                ).takeIf { renderingMode.rendersMarkup && !sourceCssPriority },
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
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_novel_css_snippets),
                    subtitle = stringResource(MR.strings.pref_novel_css_snippets_summary),
                    onClick = { navigator.push(NovelCodeSnippetsScreen(NovelSnippetKind.CSS)) },
                ).takeIf { renderingMode.rendersMarkup },
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_novel_js_snippets),
                    subtitle = stringResource(MR.strings.pref_novel_js_snippets_summary),
                    onClick = { navigator.push(NovelCodeSnippetsScreen(NovelSnippetKind.JS)) },
                ).takeIf { renderingMode.rendersMarkup },
            ),
        )
    }

    @Composable
    private fun getReadingGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val renderingMode by novelPreferences.readerRenderingMode().collectAsState()
        val seamless by novelPreferences.readerSeamlessChapters().collectAsState()
        val fullscreen by novelPreferences.readerFullscreen().collectAsState()
        val tapLayout by novelPreferences.readerTapLayout().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reading),
            preferenceItems = listOfNotNull(
                // An open reader rebuilds around its live session on a change (NovelReaderProvider).
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
                Preference.PreferenceItem.SliderPreference(
                    preference = novelPreferences.readerAutoLoadNextAt(),
                    valueRange = NovelTextRanges.autoLoadNextAtPercent,
                    title = stringResource(MR.strings.pref_novel_auto_load_next_at),
                    valueText = { "$it%" },
                ).takeIf { seamless },
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerChapterTitleFormat(),
                    entries = ChapterTitleFormat.entries.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_chapter_title_format),
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
                    visible = LocalView.current.hasDisplayCutout() && fullscreen,
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
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerShowTapZonesOnStart(),
                    title = stringResource(MR.strings.pref_show_navigation_mode),
                    subtitle = stringResource(MR.strings.pref_show_navigation_mode_summary),
                ).takeIf { tapLayout != NovelTapLayout.DISABLED },
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerTapInvert(),
                    entries = tapLayout.invertModes.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                ).takeIf { tapLayout != NovelTapLayout.DISABLED && tapLayout.invertModes.size > 1 },
                Preference.PreferenceItem.SliderPreference(
                    preference = novelPreferences.readerTapBottomZoneHeight(),
                    valueRange = NovelTapZones.BOTTOM_ZONE_PERCENT,
                    title = stringResource(MR.strings.pref_tap_bottom_zone_height),
                    valueText = { "$it%" },
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
                    preference = novelPreferences.readerMarkReadPercent(),
                    valueRange = 50..100,
                    title = stringResource(MR.strings.pref_novel_mark_read_percent),
                    valueText = { "$it%" },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerMarkReadOnSkip(),
                    title = stringResource(MR.strings.pref_mark_read_on_skip),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerAutoScrollOnOpen(),
                    title = stringResource(MR.strings.pref_auto_scroll_on_open),
                ),
                autoScrollSpeedPreference(novelPreferences.readerAutoScrollSpeed(), subtitle = null),
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
        // Gated on the rail alone, unlike the manga screen's pair: a novel has no reading mode to pick it.
        val useRail by novelPreferences.readerUseRail().collectAsState()
        val showNavigator by novelPreferences.readerShowNavigator().collectAsState()

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
                    visible = useVolumeButtons,
                ),
                volumeKeyScrollPreference(
                    novelPreferences.readerVolumeButtonsFraction(),
                    visible = useVolumeButtons,
                    subtitle = null,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerHideThreshold(),
                    entries = mapOf(
                        ReaderHideThreshold.HIGHEST to stringResource(MR.strings.pref_highest),
                        ReaderHideThreshold.HIGH to stringResource(MR.strings.pref_high),
                        ReaderHideThreshold.LOW to stringResource(MR.strings.pref_low),
                        ReaderHideThreshold.LOWEST to stringResource(MR.strings.pref_lowest),
                    ),
                    title = stringResource(MR.strings.pref_hide_threshold),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerShowNavigator(),
                    title = stringResource(MR.strings.pref_show_progress_navigator),
                    subtitle = stringResource(MR.strings.pref_show_progress_navigator_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerUseRail(),
                    title = stringResource(MR.strings.pref_novel_use_rail),
                ).takeIf { showNavigator },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerRailOnLeft(),
                    title = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
                ).takeIf { showNavigator && useRail },
                Preference.PreferenceItem.SliderPreference(
                    preference = novelPreferences.readerRailHeight(),
                    valueRange = ReaderRanges.railHeightPercent,
                    steps = ReaderRanges.railHeightSteps,
                    title = stringResource(MR.strings.pref_vertical_navigator_height),
                ).takeIf { showNavigator && useRail },
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
