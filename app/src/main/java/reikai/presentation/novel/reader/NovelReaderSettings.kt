package reikai.presentation.novel.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import reikai.presentation.reader.ReaderThemePreset
import reikai.presentation.reader.readerDarkPreset
import reikai.presentation.reader.readerLightPreset
import reikai.presentation.reader.readerThemePresets

/**
 * Resolved reader display settings, read by every rendering mode: the legacy reader hands `core.js`
 * its LNReader settings objects ([readerSettingsJson], [generalSettingsJson]), the WebView mode reads
 * only the CSS variables and behaviour flags `NovelWebDocument` builds, and the native renderer styles
 * its text views off the same fields. [followSystemTheme] is carried only so a sheet can show the
 * "Auto" state; it is already resolved into [backgroundColor] and [textColor] by the time a renderer
 * sees it.
 */
data class NovelReaderSettings(
    val fontSize: Int,
    val lineHeight: Float,
    val textAlign: String,
    val margins: ReaderMargins,
    /** First-line indent, as a multiple of [fontSize]. */
    val paragraphIndent: Float,
    /** Gap after a paragraph, as a multiple of [fontSize]. */
    val paragraphSpacing: Float,
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
    // Text-to-speech: the subset the legacy reader's `core.js` reads (general `TTSEnable` + the `tts` block).
    val ttsEnabled: Boolean,
    val ttsRate: Float,
    val ttsPitch: Float,
    val ttsAutoPageAdvance: Boolean,
    val ttsScrollToTop: Boolean,
    // Reading extras each renderer applies itself; extra spacing is stripped by the content pipeline instead.
    val bionicReading: Boolean,
    val removeExtraSpacing: Boolean,
    val tapToScroll: Boolean,
    val swipeGestures: Boolean,
    /** Always-on reading percentage overlay while reading (chrome hidden). Native Compose, not core.js. */
    val showProgressPercentage: Boolean,
    // Driven natively (not by core.js): auto-scroll runs an injected scroller.
    val autoScroll: Boolean,
    val autoScrollSpeed: Float,
    // Driven natively: hardware volume keys scroll the chapter, intercepted at the host window.
    val useVolumeButtons: Boolean,
    val volumeButtonsInverted: Boolean,
    val volumeButtonsFraction: Float,
    // Vertical progress-rail geometry, shared with the manga reader (verticalNavigator prefs).
    val railHeightPercent: Int,
    val railOnLeft: Boolean,
    /** Whether the marker between two consecutive chapters shows (`NovelSeam.isShown`). Carried here
     *  so the host's settings push redraws an open window. Defaulted because the legacy reader builds
     *  this object too and draws no marker. */
    val alwaysShowChapterTransition: Boolean = true,
)

/**
 * The reader page's four margins, in dp.
 *
 * They are one type rather than four fields because the renderers split them differently: a text
 * renderer puts the sides on every chunk view and the ends on the column that holds them, so a
 * chapter long enough to be chunked does not repeat the top margin at every seam.
 */
data class ReaderMargins(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
)

/**
 * Brightness + colour-filter overlay settings. Kept separate from [NovelReaderSettings] because they
 * render as a native Compose overlay (plus the host window's brightness) and never touch the WebView,
 * so changing them must not trigger a settings re-push to the web layer.
 */
data class NovelReaderOverlaySettings(
    val customBrightness: Boolean,
    val customBrightnessValue: Int,
    val colorFilter: Boolean,
    val colorFilterValue: Int,
    val colorFilterMode: Int,
)

/** Per-novel orientation choices in the reader sheet: Default (follow the global default) plus the
 *  concrete locks. Reverse-portrait is dropped (rarely wanted); the global-default Settings list
 *  additionally drops Default. */
val readerOrientations = ReaderOrientation.entries.filter { it != ReaderOrientation.REVERSE_PORTRAIT }

/**
 * Applies the "Auto" theme option, which every reader owes before it renders. The stored colours are
 * whatever a manual choice last left behind, so skipping this shows a reader the opposite shade of
 * the one the user is in rather than falling back to a sensible default.
 */
fun NovelReaderSettings.resolvedForSystemTheme(isDark: Boolean): NovelReaderSettings {
    if (!followSystemTheme) return this
    val preset = if (isDark) readerDarkPreset else readerLightPreset
    return copy(backgroundColor = preset.background, textColor = preset.textColor)
}

/**
 * A selectable reader font. [family] is empty for the source's own font, one of the generic CSS
 * families, a bundled `assets/fonts/<family>.ttf`, or the file name of one the user added.
 */
data class ReaderFont(val family: String, val name: String)

/** The three families Android guarantees, offered above the bundled faces. */
val readerGenericFonts = listOf(
    ReaderFont("sans-serif", "Sans serif"),
    ReaderFont("serif", "Serif"),
    ReaderFont("monospace", "Monospace"),
)

/** Bundled fonts from LNReader (Original + 9 families shipped under assets/fonts/). */
val readerFonts = listOf(
    ReaderFont("", "Default"),
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
