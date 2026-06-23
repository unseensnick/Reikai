package reikai.presentation.novel.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation

/**
 * Resolved reader display settings fed to the WebView (the LNReader `ChapterReaderSettings` subset
 * the web layer reads). [followSystemTheme] is carried so the settings sheet can show the "Auto"
 * state; [NovelReaderScreen] resolves it into the effective [backgroundColor]/[textColor] before render.
 */
data class NovelReaderSettings(
    val fontSize: Int,
    val lineHeight: Float,
    val textAlign: String,
    val padding: Int,
    val fontFamily: String,
    val followSystemTheme: Boolean,
    val backgroundColor: String,
    val textColor: String,
    val keepScreenOn: Boolean,
    /** The per-novel reader orientation `flagValue` (0 = Default, i.e. follow the global default).
     *  Drives the settings sheet's current selection. */
    val orientation: Int,
    /** [orientation] resolved against the global default: the concrete orientation the reader applies. */
    val resolvedOrientation: Int,
    // Text-to-speech: the subset the WebView's `core.js` reads (general `TTSEnable` + the `tts` block).
    val ttsEnabled: Boolean,
    val ttsRate: Float,
    val ttsPitch: Float,
    val ttsAutoPageAdvance: Boolean,
    val ttsScrollToTop: Boolean,
    // Engine extras applied by `core.js` (general settings block).
    val bionicReading: Boolean,
    val removeExtraSpacing: Boolean,
    val tapToScroll: Boolean,
    val swipeGestures: Boolean,
    // Driven natively (not by core.js): auto-scroll runs an injected scroller, the seekbar is Compose.
    val autoScroll: Boolean,
    val autoScrollSpeed: Float,
    val verticalSeekbar: Boolean,
)

/** Per-novel orientation choices in the reader sheet: Default (follow the global default) plus the
 *  concrete locks. Reverse-portrait is dropped (rarely wanted); the global-default Settings list
 *  additionally drops Default. */
val readerOrientations = ReaderOrientation.entries.filter { it != ReaderOrientation.REVERSE_PORTRAIT }

/** A reader background + text color pairing. Tapping one in the settings sheet writes both. */
data class ReaderThemePreset(val name: String, val background: String, val textColor: String)

/** The five presets from LNReader (light, sepia, mint, dark, black). */
val readerThemePresets = listOf(
    ReaderThemePreset("Light", "#f5f5fa", "#111111"),
    ReaderThemePreset("Sepia", "#F7DFC6", "#593100"),
    ReaderThemePreset("Mint", "#dce5e2", "#000000"),
    ReaderThemePreset("Dark", "#292832", "#CCCCCC"),
    ReaderThemePreset("Black", "#000000", "#FFFFFFB3"),
)

/** Presets used by the "Auto" (follow-system) option for light and dark system modes. */
val readerLightPreset = readerThemePresets.first { it.name == "Light" }
val readerDarkPreset = readerThemePresets.first { it.name == "Dark" }

/** A selectable reader font. [family] is empty for the source's original font and otherwise matches
 *  a bundled `assets/fonts/<family>.ttf` (the path the LNReader web layer loads). */
data class ReaderFont(val family: String, val name: String)

/** Bundled fonts from LNReader (Original + 9 families shipped under assets/fonts/). */
val readerFonts = listOf(
    ReaderFont("", "Original"),
    ReaderFont("lora", "Lora"),
    ReaderFont("nunito", "Nunito"),
    ReaderFont("noto-sans", "Noto Sans"),
    ReaderFont("open-sans", "Open Sans"),
    ReaderFont("arbutus-slab", "Arbutus Slab"),
    ReaderFont("domine", "Domine"),
    ReaderFont("lato", "Lato"),
    ReaderFont("pt-serif", "PT Serif"),
    ReaderFont("OpenDyslexic3-Regular", "OpenDyslexic"),
)
