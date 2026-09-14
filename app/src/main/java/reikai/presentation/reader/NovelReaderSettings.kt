package reikai.presentation.reader

import reikai.domain.novel.tts.TtsHighlightStyle

/**
 * Resolved reader display settings, read by both rendering modes: the WebView mode reads the CSS
 * variables and behaviour flags `NovelWebDocument` builds, and the native renderer styles its text
 * views off the same fields. [followSystemTheme] is carried only so a sheet can show the
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
    // How the renderers place, mark and follow the spoken paragraph (ReadAloudSurface).
    val ttsScrollToTop: Boolean,
    val ttsHighlight: Boolean,
    val ttsHighlightStyle: TtsHighlightStyle,
    /** Packed ARGB. */
    val ttsHighlightColor: Int,
    /** Packed ARGB, the text over a [TtsHighlightStyle.BACKGROUND] mark. */
    val ttsHighlightTextColor: Int,
    val ttsKeepInView: Boolean,
    // Reading extras each renderer applies itself; extra spacing is stripped by the content pipeline instead.
    val bionicReading: Boolean,
    val tapToScroll: Boolean,
    val swipeGestures: Boolean,
    /** Always-on reading percentage overlay while reading (chrome hidden). */
    val showProgressPercentage: Boolean,
    val autoScroll: Boolean,
    val autoScrollSpeed: Float,
    val useVolumeButtons: Boolean,
    val volumeButtonsInverted: Boolean,
    val volumeButtonsFraction: Float,
    // Vertical progress-rail geometry, shared with the manga reader (verticalNavigator prefs).
    val railHeightPercent: Int,
    val railOnLeft: Boolean,
    /** Whether the marker between two consecutive chapters shows (`NovelSeam.isShown`). Carried here
     *  so the host's settings push redraws an open window. */
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
