package reikai.presentation.reader

import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelTextAlign
import reikai.domain.novel.tts.TtsHighlightStyle
import reikai.novel.font.GenericFontFamily
import reikai.novel.font.ReaderFontSource
import reikai.novel.font.fontDisplayName
import reikai.novel.font.readerFontSource
import reikai.presentation.reader.web.NovelWebSnippets
import tachiyomi.i18n.MR

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
    val textAlign: NovelTextAlign,
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
    val tapZones: NovelTapZones,
    val swipeGestures: Boolean,
    /** Always-on reading percentage overlay while reading (chrome hidden). */
    val showProgressPercentage: Boolean,
    val useVolumeButtons: Boolean,
    val volumeButtonsInverted: Boolean,
    val volumeButtonsFraction: Float,
    // The progress navigator's shape, the novel reader's own (NovelPreferences.readerUseRail).
    val railHeightPercent: Int,
    val railOnLeft: Boolean,
    /** The vertical rail, or the horizontal slider above the bar's buttons. */
    val useRail: Boolean,
    /** Whether the marker between two consecutive chapters shows (`NovelSeam.isShown`). Carried here
     *  so the host's settings push redraws an open window. */
    val alwaysShowChapterTransition: Boolean = true,
    /** The user's CSS and JavaScript, which only the WebView renderer applies. */
    val webSnippets: NovelWebSnippets = NovelWebSnippets(),
) {
    companion object {
        /** The settings as stored now, for a novel whose own orientation flag is [orientation]. */
        fun read(preferences: NovelPreferences, orientation: Int): NovelReaderSettings = with(preferences) {
            NovelReaderSettings(
                fontSize = readerFontSize().get(),
                lineHeight = readerLineSpacing().get(),
                textAlign = readerTextAlign().get(),
                margins = ReaderMargins(
                    top = readerMarginTop().get(),
                    bottom = readerMarginBottom().get(),
                    left = readerMarginLeft().get(),
                    right = readerMarginRight().get(),
                ),
                paragraphIndent = readerParagraphIndent().get(),
                paragraphSpacing = readerParagraphSpacing().get(),
                fontFamily = readerFontFamily().get(),
                followSystemTheme = readerFollowSystemTheme().get(),
                backgroundColor = readerBackgroundColor().get(),
                textColor = readerTextColor().get(),
                keepScreenOn = readerKeepScreenOn().get(),
                orientation = orientation,
                ttsScrollToTop = readerTtsScrollToTop().get(),
                ttsHighlight = readerTtsHighlight().get(),
                ttsHighlightStyle = readerTtsHighlightStyle().get(),
                ttsHighlightColor = readerTtsHighlightColor().get(),
                ttsHighlightTextColor = readerTtsHighlightTextColor().get(),
                ttsKeepInView = readerTtsKeepInView().get(),
                bionicReading = readerBionicReading().get(),
                tapZones = NovelTapZones(
                    readerTapLayout().get(),
                    readerTapInvert().get(),
                    readerTapBottomZoneHeight().get(),
                ),
                swipeGestures = readerSwipeGestures().get(),
                showProgressPercentage = readerShowProgressPercentage().get(),
                useVolumeButtons = readerUseVolumeButtons().get(),
                volumeButtonsInverted = readerVolumeButtonsInverted().get(),
                volumeButtonsFraction = readerVolumeButtonsFraction().get(),
                railHeightPercent = readerRailHeight().get(),
                railOnLeft = readerRailOnLeft().get(),
                useRail = readerUseRail().get(),
                alwaysShowChapterTransition = readerAlwaysShowChapterTransition().get(),
                webSnippets = NovelWebSnippets.from(readerCssSnippets().get(), readerJsSnippets().get()),
            )
        }

        // Must name every preference [read] reads; NovelReaderSettingsTest compares the two.
        fun watched(preferences: NovelPreferences): List<Flow<*>> = with(preferences) {
            listOf(
                readerFontSize().changes(),
                readerLineSpacing().changes(),
                readerTextAlign().changes(),
                readerMarginTop().changes(),
                readerMarginBottom().changes(),
                readerMarginLeft().changes(),
                readerMarginRight().changes(),
                readerParagraphIndent().changes(),
                readerParagraphSpacing().changes(),
                readerFontFamily().changes(),
                readerFollowSystemTheme().changes(),
                readerBackgroundColor().changes(),
                readerTextColor().changes(),
                readerKeepScreenOn().changes(),
                readerTtsScrollToTop().changes(),
                readerTtsHighlight().changes(),
                readerTtsHighlightStyle().changes(),
                readerTtsHighlightColor().changes(),
                readerTtsHighlightTextColor().changes(),
                readerTtsKeepInView().changes(),
                readerBionicReading().changes(),
                readerTapLayout().changes(),
                readerTapInvert().changes(),
                readerTapBottomZoneHeight().changes(),
                readerSwipeGestures().changes(),
                readerShowProgressPercentage().changes(),
                readerUseVolumeButtons().changes(),
                readerVolumeButtonsInverted().changes(),
                readerVolumeButtonsFraction().changes(),
                readerRailHeight().changes(),
                readerRailOnLeft().changes(),
                readerUseRail().changes(),
                readerAlwaysShowChapterTransition().changes(),
                readerCssSnippets().changes(),
                readerJsSnippets().changes(),
            )
        }
    }
}

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
    val shown = readerThemeShown(followSystemTheme, isDark, backgroundColor, textColor)
    return copy(backgroundColor = shown.background, textColor = shown.textColor)
}

/** The colours the page shows: the stored ones, or under follow-system the preset the system's shade
 *  picks. The settings sheet shows the same, so its swatches are what the renderers draw. */
fun readerThemeShown(followSystem: Boolean, isDark: Boolean, background: String, textColor: String): ReaderThemePreset =
    when {
        !followSystem -> ReaderThemePreset("", background, textColor)
        isDark -> readerDarkPreset
        else -> readerLightPreset
    }

/**
 * A selectable reader font. [family] is empty for the source's own font, one of the generic CSS
 * families, a bundled `assets/fonts/<family>.ttf`, or the file name of one the user added.
 */
data class ReaderFont(val family: String, val name: String)

/** The three families Android guarantees, offered above the bundled faces. */
private val readerGenericFonts = GenericFontFamily.entries.map { ReaderFont(it.css, it.label) }

/** Bundled fonts from LNReader (Default + 9 families shipped under assets/fonts/). */
private val readerFonts = listOf(
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

/** The source's own font first because it is the default, then the families every device has, then the
 *  bundled faces. The one order every font list shows. */
val builtInReaderFonts: List<ReaderFont> = readerFonts.take(1) + readerGenericFonts + readerFonts.drop(1)

/** A font's name as every screen shows it: [defaultLabel] for the source's own, else a built-in's name,
 *  else the name of the file the user added. */
fun readerFontLabel(family: String, defaultLabel: String): String = if (isSourceDefaultFont(family)) {
    defaultLabel
} else {
    builtInReaderFonts.firstOrNull { it.family == family }?.name ?: fontDisplayName(family)
}

/** The line under a font's name in every font list. Only the default sets no font, so only it says what
 *  draws instead. */
fun readerFontSummary(family: String): StringResource? =
    MR.strings.pref_novel_font_default_summary.takeIf { isSourceDefaultFont(family) }

private fun isSourceDefaultFont(family: String) = readerFontSource(family) == ReaderFontSource.SourceDefault
