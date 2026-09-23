package reikai.domain.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.tts.TtsHighlightColors
import reikai.domain.novel.tts.TtsHighlightStyle
import reikai.domain.reader.CONTINUOUS_COMPLETE_PERCENT
import reikai.domain.reader.ChapterTitleFormat
import reikai.domain.source.NovelIconHints
import reikai.novel.content.NovelSnippetKind
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Net-new preferences for the light-novel vertical. Only the subset the plugin host / source /
 * install / update layers need lands here; later stages (reader, library, merge) grow this
 * holder. Key strings are stored on every install and restored verbatim from backups, so renaming
 * one needs a migration that moves the value.
 */
@Inject
@SingleIn(AppScope::class)
class NovelPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Canonicalized plugin .js URLs the user has installed. The plugin id is not stored here: it is
     * read from each plugin after load, so an unloadable plugin (404, parse error) can still be
     * uninstalled by removing its URL.
     */
    fun installedPluginUrls() = preferenceStore.getStringSet(INSTALLED_PLUGIN_URLS_KEY, emptySet())

    /**
     * Set by backup restore when [installedPluginUrls] came from the backup. A restored set can carry
     * arbitrary plugin .js URLs that auto-load and get evaluated, so LnPluginInstaller validates them
     * against the added repos before loading any, then clears this flag.
     */
    fun pluginsNeedRevalidation() = preferenceStore.getBoolean(PLUGINS_NEED_REVALIDATION_KEY, false)

    /**
     * Per-plugin metadata side-table keyed by the same canonicalized URL as [installedPluginUrls].
     * Carries the registry's icon URL (so the sources list renders real icons), version, and lang.
     */
    fun installedPluginMetadata() = preferenceStore.getObjectFromString(
        key = "ln_installed_plugin_metadata",
        defaultValue = emptyMap(),
        serializer = { metadataJson.encodeToString(metadataMapSerializer, it) },
        deserializer = {
            runCatching { metadataJson.decodeFromString(metadataMapSerializer, it) }.getOrElse { emptyMap() }
        },
    )

    /**
     * Last-known display identity (name, icon, lang) per plugin id, kept so the Browse migration list
     * can render a source whose plugin is no longer installed (manga-stub parity). Written on every
     * source load/install and deliberately NOT pruned on uninstall, so the row survives removal. A
     * source never seen on this device (e.g. a backup restore) is absent and falls back to its raw id.
     */
    fun seenNovelSources() = preferenceStore.getObjectFromString(
        key = "ln_seen_novel_sources",
        defaultValue = emptyMap(),
        serializer = { metadataJson.encodeToString(seenSourcesMapSerializer, it) },
        deserializer = {
            runCatching { metadataJson.decodeFromString(seenSourcesMapSerializer, it) }.getOrElse { emptyMap() }
        },
    )

    /** Icons for a novel app whose own icon shows nothing, gathered from the store and repo listings. */
    fun novelIconHints() = preferenceStore.getObjectFromString(
        key = "novel_icon_hints",
        defaultValue = NovelIconHints(),
        serializer = { metadataJson.encodeToString(NovelIconHints.serializer(), it) },
        deserializer = {
            runCatching {
                metadataJson.decodeFromString(NovelIconHints.serializer(), it)
            }.getOrElse { NovelIconHints() }
        },
    )

    /**
     * Adds to [novelIconHints], writing only when something changed. Locked, since repos fetched in
     * parallel each read, merge and write, and an unlocked write drops the other's hints.
     */
    fun addIconHints(
        packages: Map<String, String>,
        siteIcons: List<Pair<String?, String>>,
    ) = synchronized(iconHintsLock) {
        val hints = novelIconHints()
        val current = hints.get()
        val updated = current.plus(packages, siteIcons)
        if (updated != current) hints.set(updated)
    }

    private val iconHintsLock = Any()

    /**
     * Plugin repo URLs (i.e. `plugins.min.json` registries) the user added. Distinct from
     * [installedPluginUrls]: this tracks repos (sources of plugins); that tracks the individual
     * `.js` URLs actually installed.
     */
    fun addedRepoUrls() = preferenceStore.getStringSet("novel_added_repo_urls", emptySet())

    /** Count of installed plugins whose stored version is older than the latest registry version. */
    fun pluginUpdatesCount() = preferenceStore.getInt("ln_plugin_updates_count", 0)

    /** Last successful update-check timestamp (millis); gates the on-launch path to skip if recent. */
    fun lastLnPluginCheck() = preferenceStore.getLong("ln_plugin_last_check", 0L)

    /** Epoch-millis the last novel library update started; feeds the shared Updates "Last updated"
     *  line. App-state (not backed up), mirroring manga's [LibraryPreferences.lastUpdatedTimestamp]. */
    fun novelLibraryUpdateLastTimestamp() =
        preferenceStore.getLong(Preference.appStateKey("novel_library_update_last_timestamp"), 0L)

    // Global chapter sort / filter / display defaults. A novel falls back to these unless its
    // own `chapterFlags` local bit is set (see [reikai.domain.novel.model.NovelChapterFlags]). Stored
    // as the same bitmask values the per-novel flags use, so "Set as default" is a straight copy.

    /** Default chapter sort method (source order / number / upload date) as the SORTING_MASK bits. */
    fun defaultChapterSortOrder() = preferenceStore.getLong("ln_default_chapter_sort", 0L)

    /** Default chapter sort direction; true = newest/highest first (matches the manga default). */
    fun defaultChapterSortDescending() = preferenceStore.getBoolean("ln_default_chapter_sort_desc", true)

    /** Default unread filter as the READ_MASK bits (0 = show all). */
    fun defaultChapterFilterUnread() = preferenceStore.getLong("ln_default_chapter_filter_unread", 0L)

    /** Default bookmarked filter as the BOOKMARKED_MASK bits (0 = show all). */
    fun defaultChapterFilterBookmarked() = preferenceStore.getLong("ln_default_chapter_filter_bookmarked", 0L)

    /** Default downloaded filter as the DOWNLOADED_MASK bits (0 = show all). */
    fun defaultChapterFilterDownloaded() = preferenceStore.getLong("ln_default_chapter_filter_downloaded", 0L)

    /** Default display: true shows "Chapter N", false shows the source chapter title. */
    fun defaultChapterHideTitles() = preferenceStore.getBoolean("ln_default_chapter_hide_titles", false)

    /**
     * Chapters the user manually hid (e.g. a source's own duplicate listings). Each entry is the
     * restore-stable key `"<source>|<chapterUrl>"` (no local novel id, so it survives a backup
     * restore where novels are re-inserted with new ids). Backed up automatically as a normal pref.
     */
    fun hiddenChapters() = preferenceStore.getStringSet("novel_hidden_chapters", emptySet())

    // Reader display + theme. Renaming a key needs a migration that moves the value (see the class KDoc).

    fun readerFontSize() = preferenceStore.getInt("ln_reader_font_size_sp", 16)
    fun readerLineSpacing() = preferenceStore.getFloat("ln_reader_line_spacing", 1.5f)
    fun readerTextAlign() = preferenceStore.getString("ln_reader_text_align", "left")
    fun readerFontFamily() = preferenceStore.getString("ln_reader_font_family", "")

    /**
     * The four page margins, in dp, at tsundoku's defaults. The top is the odd one out on purpose:
     * it clears the status bar and the reader's own top bar. They replaced a single padding value,
     * whose key [DEAD_READER_PADDING_KEY] is carried into the sides by [carryReaderPaddingToMargins].
     */
    fun readerMarginTop() = preferenceStore.getInt("ln_reader_margin_top", 50)
    fun readerMarginBottom() = preferenceStore.getInt("ln_reader_margin_bottom", 16)
    fun readerMarginLeft() = preferenceStore.getInt("ln_reader_margin_left", 16)
    fun readerMarginRight() = preferenceStore.getInt("ln_reader_margin_right", 16)

    /**
     * Carries a retired [DEAD_READER_PADDING_KEY] value into the left and right margins, the edges it
     * padded in every build that shipped it; top and bottom keep their own defaults. Shared, because a
     * backup restore owes the same carry as the upgrade migration and can land the old key after that
     * migration has already run.
     */
    fun carryReaderPaddingToMargins(padding: Int) {
        readerMarginLeft().set(padding)
        readerMarginRight().set(padding)
    }

    /**
     * First-line indent and the gap between paragraphs, both as a multiple of the font size so they
     * scale with it. Spacing is the whole gap rather than an addition to one, so zero genuinely
     * closes it up, and the default is chosen by eye rather than taken from tsundoku's 0.5: theirs
     * sits on top of a blank line our renderer removes, which makes the same number draw differently.
     */
    fun readerParagraphIndent() = preferenceStore.getFloat("ln_reader_paragraph_indent", 0f)
    fun readerParagraphSpacing() = preferenceStore.getFloat("ln_reader_paragraph_spacing", 1.5f)

    /** Hold the screen awake while reading (Android FLAG_KEEP_SCREEN_ON). Separate from the manga
     *  reader's key on purpose, not twin debt to unify: see docs/dev/plans/settings-restructure.md. */
    fun readerKeepScreenOn() = preferenceStore.getBoolean("ln_reader_keep_screen_on", false)

    /** Default reader orientation for novels with no per-novel override, the novel twin of the manga
     *  reader's `defaultOrientationType`. Stores a [ReaderOrientation] `flagValue`. */
    fun readerDefaultOrientation() =
        preferenceStore.getInt("ln_reader_default_orientation", ReaderOrientation.FREE.flagValue)

    /** Which renderer a novel opens in. Native is the default, as it is in tsundoku. */
    fun readerRenderingMode() =
        preferenceStore.getEnum("ln_reader_rendering_mode", NovelRenderingMode.NATIVE)

    /** When true the reader follows the system light/dark mode; otherwise the chosen preset wins. */
    fun readerFollowSystemTheme() = preferenceStore.getBoolean("ln_reader_follow_system_theme", true)
    fun readerBackgroundColor() = preferenceStore.getString("ln_reader_bg_color", "#292832")
    fun readerTextColor() = preferenceStore.getString("ln_reader_text_color", "#CCCCCC")

    // The page's brightness and colour treatment (ReaderDisplayFilters), the novel reader's own values.
    // The first five keys are the old novel reader's, so what a user set there carries over.
    fun readerCustomBrightness() = preferenceStore.getBoolean("ln_reader_custom_brightness", false)
    fun readerCustomBrightnessValue() = preferenceStore.getInt("ln_reader_custom_brightness_value", 0)
    fun readerColorFilter() = preferenceStore.getBoolean("ln_reader_color_filter", false)
    fun readerColorFilterValue() = preferenceStore.getInt("ln_reader_color_filter_value", 0)
    fun readerColorFilterMode() = preferenceStore.getInt("ln_reader_color_filter_mode", 0)
    fun readerGrayscale() = preferenceStore.getBoolean("ln_reader_grayscale", false)
    fun readerInvertedColors() = preferenceStore.getBoolean("ln_reader_inverted_colors", false)

    /** When on, the reader's next/previous skip a chapter whose number matches the one just read (the
     *  same-number duplicates a cross-source merge produces). Reading-navigation only, non-destructive. */
    fun readerSkipDuplicateChapters() = preferenceStore.getBoolean("ln_reader_skip_duplicate_chapters", false)

    /**
     * Forward-only skips, the novel twins of the manga reader's `skipRead` / `skipFiltered`. Their own
     * keys rather than the manga ones, so a reader can skip read chapters in one library and not the
     * other; the defaults match manga's so the two behave alike until someone changes one.
     */
    fun readerSkipRead() = preferenceStore.getBoolean("ln_reader_skip_read", false)

    fun readerSkipFiltered() = preferenceStore.getBoolean("ln_reader_skip_filtered", true)

    /**
     * The progress rail's side and height. Unlike the manga reader's pair these are never gated on a
     * reading mode, only on [readerUseRail]; the manga rows are hidden until a vertical navigator is
     * switched on, which used to leave a novel reader's rail configured by settings the user could not
     * see. Defaults match the manga ones.
     */
    fun readerRailOnLeft() = preferenceStore.getBoolean("ln_reader_rail_on_left", false)

    /** The vertical rail on the page's edge, or off for the horizontal slider above the bar's buttons. */
    fun readerUseRail() = preferenceStore.getBoolean("ln_reader_use_rail", true)

    /** Off hides the rail and the slider both, moving the chapter buttons into the bar. The manga twin is
     *  `ReaderPreferences.showNavigator`, and both answer the host through `ReaderNavigatorShape`. */
    fun readerShowNavigator() = preferenceStore.getBoolean("ln_reader_show_navigator", true)

    /**
     * Whether a novel session hides the system bars and draws under the cutout. Its own pair for the
     * same reason the rail's is: the manga screen's switches are where a novel reader cannot find
     * them, and the two readers are configured from different screens. Defaults match manga's.
     */
    fun readerFullscreen() = preferenceStore.getBoolean("ln_reader_fullscreen", true)

    fun readerDrawUnderCutout() = preferenceStore.getBoolean("ln_reader_cutout_short", true)

    fun readerRailHeight() = preferenceStore.getInt("ln_reader_rail_height", 65)

    /** When on, tapping "next" marks the chapter you skipped away from as read (forward only), the novel
     *  twin of the manga reader's mark-read-on-skip. Opt-in. */
    fun readerMarkReadOnSkip() = preferenceStore.getBoolean("ln_reader_mark_read_on_skip", false)

    // Text-to-speech, read by ReadAloudController and the settings screen.

    /** Chosen `TextToSpeech` engine package (e.g. `com.google.android.tts`); empty = system default. */
    fun readerTtsEngine() = preferenceStore.getString("ln_reader_tts_engine", "")

    /** Chosen voice name within the engine (the `Voice.name` id); empty = engine default. */
    fun readerTtsVoice() = preferenceStore.getString("ln_reader_tts_voice", "")

    /** Sets the engine. A voice belongs to the engine offering it, so switching engines clears the voice. */
    fun setReaderTtsEngine(enginePackage: String) {
        if (enginePackage != readerTtsEngine().get()) readerTtsVoice().set("")
        readerTtsEngine().set(enginePackage)
    }

    /** Base language codes (e.g. `en`, `ja`) the voice picker is filtered to. Empty = show every
     *  language the engine offers. */
    fun readerTtsLanguages() = preferenceStore.getStringSet("ln_reader_tts_languages", emptySet())

    /** Speech rate multiplier (0.1..5.0; 1.0 = normal). */
    fun readerTtsRate() = preferenceStore.getFloat("ln_reader_tts_rate", 1.0f)

    /** Speech pitch multiplier (0.1..5.0; 1.0 = normal). */
    fun readerTtsPitch() = preferenceStore.getFloat("ln_reader_tts_pitch", 1.0f)

    /** When the chapter finishes reading aloud, auto-advance to the next chapter and keep reading. */
    fun readerTtsAutoPageAdvance() = preferenceStore.getBoolean("ln_reader_tts_auto_page_advance", false)

    /** Scroll the spoken paragraph near the top (vs centering it) as TTS advances. */
    fun readerTtsScrollToTop() = preferenceStore.getBoolean("ln_reader_tts_scroll_to_top", true)

    /** Mark the paragraph being spoken. Off still follows it on screen when [readerTtsKeepInView] is on. */
    fun readerTtsHighlight() = preferenceStore.getBoolean("ln_reader_tts_highlight", true)

    /** Mark the sentence being spoken rather than its paragraph, which speaks one sentence per utterance. */
    fun readerTtsHighlightSentence() = preferenceStore.getBoolean("ln_reader_tts_highlight_sentence", false)

    fun readerTtsHighlightStyle() =
        preferenceStore.getEnum("ln_reader_tts_highlight_style", TtsHighlightStyle.BACKGROUND)

    /** Packed ARGB: the mark itself, and the text drawn over a background mark. */
    fun readerTtsHighlightColor() =
        preferenceStore.getInt("ln_reader_tts_highlight_color", TtsHighlightColors.DEFAULT_HIGHLIGHT)

    fun readerTtsHighlightTextColor() =
        preferenceStore.getInt("ln_reader_tts_highlight_text_color", TtsHighlightColors.DEFAULT_TEXT)

    /** Scroll the spoken paragraph back on screen when it is not fully on it. */
    fun readerTtsKeepInView() = preferenceStore.getBoolean("ln_reader_tts_keep_in_view", true)

    /** Whether the read-aloud controls float over the reader. Hiding them leaves playback running. */
    fun readerTtsControlsVisible() = preferenceStore.getBoolean("ln_reader_tts_controls_visible", false)

    /** Bold the start of each word (bionic reading) to ease skimming. */
    fun readerBionicReading() = preferenceStore.getBoolean("ln_reader_bionic_reading", false)

    /**
     * Opens the WebView reader to Chrome's remote inspector and shows its script errors as toasts. Off by
     * default: the inspector switch is process-wide, so it opens every WebView in the app while on.
     */
    fun readerWebViewDevTools() = preferenceStore.getBoolean(WEBVIEW_DEV_TOOLS_KEY, false)

    /** Show a chapter as the markup it carries, as text, to see what a source actually sends. */
    fun readerShowRawHtml() = preferenceStore.getBoolean("ln_reader_show_raw_html", false)

    /** Collapse large runs of blank space between paragraphs. */
    fun readerRemoveExtraSpacing() = preferenceStore.getBoolean("ln_reader_remove_extra_spacing", false)

    /** Show the always-on reading percentage while reading (chrome hidden), the novel twin of the manga
     *  reader's "Show page number". Native Compose overlay; on by default (matches manga and LNReader). */
    fun readerShowProgressPercentage() = preferenceStore.getBoolean("ln_reader_show_progress_percentage", true)

    /** How far into a chapter, as a whole percent, a novel counts it as read. */
    fun readerMarkReadPercent() = preferenceStore.getInt("ln_reader_mark_read_percent", CONTINUOUS_COMPLETE_PERCENT)

    /** What the reader's bar calls the open chapter. Name, the default, is what the bar always showed. */
    fun readerChapterTitleFormat() = preferenceStore.getEnum(
        "ln_reader_chapter_title_format",
        ChapterTitleFormat.NAME,
    )

    /** How a tap on the page is read. Disabled, the default, toggles the chrome wherever the page is tapped. */
    fun readerTapLayout() = preferenceStore.getEnum("ln_reader_tap_layout", NovelTapLayout.DISABLED)

    fun readerTapInvert() = preferenceStore.getEnum("ln_reader_tap_invert", TappingInvertMode.NONE)

    /** Sets [layout], replacing an inversion it cannot draw with the nearest one it can, so the choice
     *  a settings row shows is the one in effect. */
    fun setReaderTapLayout(layout: NovelTapLayout) {
        readerTapLayout().set(layout)
        val invert = readerTapInvert()
        if (invert.get() in layout.invertModes) return
        val vertical = invert.get().shouldInvertVertical && TappingInvertMode.VERTICAL in layout.invertModes
        invert.set(if (vertical) TappingInvertMode.VERTICAL else TappingInvertMode.NONE)
    }

    /** The bottom layout's zone, as a percentage of the page's height. */
    fun readerTapBottomZoneHeight() = preferenceStore.getInt("ln_reader_tap_bottom_zone_height", 12)

    /**
     * Carries the retired [DEAD_READER_TAP_TO_SCROLL_KEY] switch into [readerTapLayout]: on was the thirds
     * layout, off toggled the chrome everywhere. Shared, because a backup restore lands the old key after
     * the upgrade migration has run.
     */
    fun carryReaderTapToScroll(enabled: Boolean) {
        readerTapLayout().set(if (enabled) NovelTapLayout.THIRDS else NovelTapLayout.DISABLED)
    }

    /** Swipe left / right to go to the next / previous chapter. */
    fun readerSwipeGestures() = preferenceStore.getBoolean("ln_reader_swipe_gestures", false)

    /** Continuously scroll the chapter while reading (paused while the chrome is shown). */
    fun readerAutoScroll() = preferenceStore.getBoolean("ln_reader_auto_scroll", false)

    /** Auto-scroll speed in CSS pixels per frame (~60fps). */
    fun readerAutoScrollSpeed() = preferenceStore.getFloat("ln_reader_auto_scroll_speed", 1.0f)

    /** Scroll the chapter with the hardware volume keys (down = forward), the novel twin of the manga
     *  reader's `readWithVolumeKeys`. Intercepted at the host window; off by default. */
    fun readerUseVolumeButtons() = preferenceStore.getBoolean("ln_reader_use_volume_buttons", false)

    /** Swap which volume key scrolls forward vs back, mirroring `readWithVolumeKeysInverted`. */
    fun readerVolumeButtonsInverted() = preferenceStore.getBoolean("ln_reader_volume_buttons_inverted", false)

    /** How far one volume press scrolls, as a fraction of the screen height (LNReader's default is
     *  0.75, leaving a quarter-screen overlap for reading continuity). */
    fun readerVolumeButtonsFraction() = preferenceStore.getFloat("ln_reader_volume_buttons_fraction", 0.75f)

    /** Reopen a read chapter where it was left rather than at its start, as the manga reader's
     *  [ReaderPreferences.preserveReadingPosition]. */
    fun readerPreserveReadingPosition() = preferenceStore.getBoolean("ln_reader_preserve_reading_position", false)

    /** User-selected bottom-bar buttons for the novel reader (the novel twin of the manga
     *  [ReaderPreferences.readerBottomButtons]). Values are [ReaderBottomButton.value] codes. */
    fun readerBottomButtons() =
        preferenceStore.getStringSet("ln_reader_bottom_buttons", ReaderBottomButton.NOVEL_BUTTONS_DEFAULTS)

    /** The order of [readerBottomButtons], as codes; empty draws them in declaration order. */
    fun readerBottomButtonOrder() = preferenceStore.getObjectFromString<List<String>>(
        key = "ln_reader_bottom_button_order",
        defaultValue = emptyList(),
        serializer = { it.joinToString("\n") },
        deserializer = { it.split("\n").filter(String::isNotBlank) },
    )

    /**
     * Puts the read-aloud button on a customised bar, for someone whose retired read-aloud switch was on:
     * a stored bar never sees a new default. Shared, because a backup restore owes the same carry as
     * the upgrade migration and can land the old switch after that migration has already run.
     */
    fun addReadAloudButtonToCustomisedBar() {
        val buttons = readerBottomButtons()
        if (buttons.isSet()) buttons.set(buttons.get() + ReaderBottomButton.ReadAloud.value)
    }

    // Chapter text pipeline. Applied to the chapter body before it is rendered, in the order
    // [reikai.novel.content.NovelContentPipeline] runs its stages.

    /** Drop a heading at the top of the body that repeats the chapter name the reader already shows. */
    fun readerHideChapterTitle() = preferenceStore.getBoolean("ln_reader_hide_chapter_title", false)

    /** Lowercase the whole chapter body. */
    fun readerForceLowercase() = preferenceStore.getBoolean("ln_reader_force_lowercase", false)

    /** Strip `img` / `video` / `audio` tags from the chapter body. */
    fun readerBlockMedia() = preferenceStore.getBoolean("ln_reader_block_media", false)

    /** Keep a chapter's own `style` blocks, `style` attributes and stylesheet links. Only a WebView
     *  renderer can honour this: a TextView draws CSS as visible text, so it strips it whatever this
     *  says. */
    fun readerKeepEmbeddedCss() = preferenceStore.getBoolean("ln_reader_keep_embedded_css", true)

    /** Keep a chapter's own code: its `script` blocks, event-handler attributes and `javascript:`
     *  URLs. Off by default: they are the source page's own code (ads, loaders, analytics) rather than
     *  chapter content. */
    fun readerKeepEmbeddedJs() = preferenceStore.getBoolean("ln_reader_keep_embedded_js", false)

    /** Let the chapter's own font declarations stand instead of the reader's chosen face. Only the
     *  WebView renderer can honour this, since a chapter's CSS never reaches the text renderer. */
    fun readerUseOriginalFonts() = preferenceStore.getBoolean("ln_reader_use_original_fonts", false)

    /** Let the chapter's own styling win over the reader's display settings, rather than the reader
     *  forcing size, colour and spacing over it. Off, because a source that styles for its own site
     *  otherwise overrides the theme the reader chose. */
    fun readerSourceCssPriority() = preferenceStore.getBoolean("ln_reader_source_css_priority", false)

    /** Let a long-press select the chapter text. The native renderer gives up following links for it,
     *  because selection needs the movement method that dispatches the drag. The WebView one keeps its
     *  links. */
    fun readerTextSelectable() = preferenceStore.getBoolean("ln_reader_text_selectable", false)

    /** Keep the neighbouring chapters loaded so reading runs on past a chapter's end. Off makes each
     *  chapter its own page, which some readers want as the place they stop. */
    fun readerSeamlessChapters() = preferenceStore.getBoolean("ln_reader_seamless_chapters", true)

    /** How far into a chapter, as a whole percent, the next one is added below it. It is fetched on open either way. */
    fun readerAutoLoadNextAt() = preferenceStore.getInt("ln_reader_auto_load_next_at", 95)

    /** The novel reader's own copy of manga's `alwaysShowChapterTransition` setting, because each reader
     *  keeps its own settings. Off draws the marker between two chapters only where chapters are missing;
     *  the marker after the last chapter shows either way. */
    fun readerAlwaysShowChapterTransition() =
        preferenceStore.getBoolean("ln_reader_always_show_chapter_transition", true)

    /** Insert paragraph breaks into chapters that arrive as one unbroken block of text. */
    fun readerAutoSplitText() = preferenceStore.getBoolean("ln_reader_auto_split_text", false)

    /** Words to pass before an auto-split break looks for the next sentence end. The splitter floors
     *  this at 20, so a smaller value does not shred the text. */
    fun readerAutoSplitWordCount() = preferenceStore.getInt("ln_reader_auto_split_word_count", 50)

    /** User find/replace rules, a JSON array of [reikai.novel.content.NovelRegexReplacement]. */
    fun readerRegexReplacements() = preferenceStore.getString("ln_reader_regex_replacements", "[]")

    /** CSS the WebView reader adds to every chapter page, a JSON array of [reikai.novel.content.NovelCodeSnippet]. */
    fun readerCssSnippets() = preferenceStore.getString(CSS_SNIPPETS_KEY, "[]")

    /** JavaScript the WebView reader runs on every chapter page, in the same shape as [readerCssSnippets]. */
    fun readerJsSnippets() = preferenceStore.getString(JS_SNIPPETS_KEY, "[]")

    fun readerSnippets(kind: NovelSnippetKind) = when (kind) {
        NovelSnippetKind.CSS -> readerCssSnippets()
        NovelSnippetKind.JS -> readerJsSnippets()
    }

    // Library.

    /** Category a newly favorited novel auto-lands in, the novel twin of manga's
     *  [LibraryPreferences.defaultCategory]. -1 = prompt for a category when the user has any. */
    fun defaultNovelCategory() = preferenceStore.getInt("default_novel_category", -1)

    /** Hide the inline "N missing chapters" separators in the details chapter list, the novel twin of
     *  manga's [LibraryPreferences.hideMissingChapters]. The header warning stays regardless. */
    fun hideMissingChapters() = preferenceStore.getBoolean("novel_hide_missing_chapters", false)

    // Downloads. Renaming a key needs a migration that moves the value (see the class KDoc).

    /** Delete a downloaded chapter's offline copy once it's marked read. */
    fun removeAfterMarkedAsRead() = preferenceStore.getBoolean("novel_remove_after_marked_as_read", false)

    /** Keep only the last N read chapters downloaded (a rolling buffer), the novel twin of manga's
     *  `removeAfterReadSlots`. -1 = off; 0 = delete the just-read chapter; 1 = keep 1 back, etc. When
     *  set (>= 0) it takes precedence over [removeAfterMarkedAsRead]. */
    fun removeAfterReadSlots() = preferenceStore.getInt("novel_remove_after_read_slots", -1)

    /** When false (default), never auto-delete a bookmarked chapter on read. Twin of manga's
     *  `removeBookmarkedChapters`. */
    fun removeBookmarkedChapters() = preferenceStore.getBoolean("novel_remove_bookmarked", false)

    /** Category ids whose novels' chapters are never auto-deleted on read. Twin of manga's
     *  `removeExcludeCategories`. */
    fun removeExcludeCategories() = preferenceStore.getStringSet("novel_remove_exclude_categories", emptySet())

    /** Download the next N unread, un-downloaded chapters as you read (download-ahead). 0 = off. Twin
     *  of manga's `autoDownloadWhileReading`, pinned by the kernel both readers call,
     *  `chaptersToDownloadAhead` in `reikai/domain/reader/ChapterNeighbours.kt`. */
    fun autoDownloadWhileReading() = preferenceStore.getInt("novel_auto_download_while_reading", 0)

    /** The shortest wait between two downloaded chapters from one source; see `NovelDownloadPacing`. */
    fun downloadChapterDelayMs() = preferenceStore.getLong("novel_download_chapter_delay_ms", 500L)

    /** A source's own delay, overriding [downloadChapterDelayMs], as `sourceId=ms` entries. */
    fun downloadSourceDelays() = preferenceStore.getStringSet("novel_download_source_delays", emptySet())

    /** Auto-download newly fetched chapters when an update is detected. The pref + download-manager
     *  plumbing exist; the update-detection trigger that consumes it is wired into the background
     *  update job. */
    fun downloadNewChapters() = preferenceStore.getBoolean("novel_download_new_chapters", false)

    /** When auto-downloading, skip a new chapter whose number matches one already read (avoids
     *  re-downloading a source's duplicate listing of a chapter you've finished). */
    fun downloadNewUnreadChaptersOnly() =
        preferenceStore.getBoolean("novel_download_new_unread_chapters_only", false)

    /** Restrict auto-download to novels in these categories (empty = all). Mirrors the manga keys. */
    fun downloadNewChapterCategories() = preferenceStore.getStringSet("novel_download_new_categories", emptySet())

    fun downloadNewChapterCategoriesExclude() =
        preferenceStore.getStringSet("novel_download_new_categories_exclude", emptySet())

    // Background chapter updates.

    /** How often the background novel-update job runs, in hours. 0 = off (the default, matching the
     *  manga library's off-by-default); 12/24/48/72/168 mirror the manga interval options. */
    fun libraryUpdateInterval() = preferenceStore.getInt("novel_library_update_interval", 0)

    /** Device conditions gating the background job, reusing the manga restriction keys
     *  ([LibraryPreferences.DEVICE_ONLY_ON_WIFI] etc.) so the same Constraints builder applies. */
    fun libraryUpdateDeviceRestrictions() =
        preferenceStore.getStringSet(
            "novel_library_update_restrictions",
            setOf(LibraryPreferences.DEVICE_ONLY_ON_WIFI),
        )

    /** Smart-update restrictions, reusing the manga restriction keys ([LibraryPreferences.MANGA_HAS_UNREAD]
     *  etc.) for the same meaning, and defaulting to the set manga's `autoUpdateMangaRestrictions` does. */
    fun novelUpdateRestrictions() = preferenceStore.getStringSet(
        "novel_library_smart_update",
        setOf(
            LibraryPreferences.MANGA_HAS_UNREAD,
            LibraryPreferences.MANGA_NON_COMPLETED,
            LibraryPreferences.MANGA_NON_READ,
            LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
        ),
    )

    /** Categories to include / exclude from the background update (mirrors the manga update categories). */
    fun novelUpdateCategories() = preferenceStore.getStringSet("novel_library_update_categories", emptySet())
    fun novelUpdateCategoriesExclude() =
        preferenceStore.getStringSet("novel_library_update_categories_exclude", emptySet())

    // Source migration.

    /** Last selection in the migrate dialog, as a [NovelMigrationFlag] bitmask. Defaults to all on. */
    fun novelMigrationFlags() = preferenceStore.getInt("novel_migration_flags", NovelMigrationFlag.DEFAULT_BITS)

    /** Hide rows whose search found nothing. Twin of the manga migration pref. */
    fun novelMigrationHideUnmatched() = preferenceStore.getBoolean("novel_migration_hide_unmatched", false)

    /** Hide rows whose match is no further ahead than the entry already is. */
    fun novelMigrationHideWithoutUpdates() =
        preferenceStore.getBoolean("novel_migration_hide_without_updates", false)

    companion object {
        // Referenced by backup restore (PreferenceRestorer) to flag restored plugin URLs for
        // validation against the added repos before the host evaluates any.
        const val INSTALLED_PLUGIN_URLS_KEY = "ln_installed_plugin_urls"
        const val PLUGINS_NEED_REVALIDATION_KEY = "ln_plugins_need_revalidation"

        private val metadataMapSerializer =
            MapSerializer(String.serializer(), LnInstalledPluginMetadata.serializer())
        private val seenSourcesMapSerializer =
            MapSerializer(String.serializer(), LnSourceIdentity.serializer())
        private val metadataJson = Json { ignoreUnknownKeys = true }

        const val CSS_SNIPPETS_KEY = "ln_reader_css_snippets"

        /** Named for the restorer, which switches every restored JavaScript snippet off. */
        const val JS_SNIPPETS_KEY = "ln_reader_js_snippets"

        /** Named for the restorer, which never restores the WebView developer tools switched on. */
        const val WEBVIEW_DEV_TOOLS_KEY = "ln_reader_webview_dev_tools"
    }
}

/**
 * The novel reader's retired single-padding key, superseded by the four margins. Its accessor is gone;
 * [mihon.core.migration.migrations.SplitNovelReaderPaddingMigration] and the backup restorer still read
 * it, each through [NovelPreferences.carryReaderPaddingToMargins], which carries it into the left and
 * right margins only.
 */
const val DEAD_READER_PADDING_KEY = "ln_reader_padding"

/** The novel reader's retired tap-to-scroll switch, carried into the tap layouts by [NovelPreferences.carryReaderTapToScroll]. */
const val DEAD_READER_TAP_TO_SCROLL_KEY = "ln_reader_tap_to_scroll"

/**
 * Keys only the retired standalone novel reader wrote: its read-aloud master switch and the floating
 * play control's position. [mihon.core.migration.migrations.AddReadAloudBottomButtonMigration] still
 * reads the switch before [mihon.core.migration.migrations.RetireLegacyNovelReaderKeysMigration] deletes
 * all three.
 */
const val DEAD_READER_TTS_ENABLED_KEY = "ln_reader_tts_enabled"
val DEAD_READER_TTS_BUTTON_KEYS = listOf("ln_reader_tts_button_x", "ln_reader_tts_button_y")
