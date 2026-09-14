package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import reikai.domain.novel.NovelTapLayout
import reikai.domain.novel.tts.TtsHighlightStyle

/**
 * A plain settings value for the tests that render a real chapter, in the WebView and the native
 * renderer alike, so none of them carries the constructor. Nothing here is the subject of a test; a
 * case that cares about a field copies this and says so, and a field changed here reaches them all.
 */
internal val readerTestSettings = NovelReaderSettings(
    fontSize = 18,
    lineHeight = 1.6f,
    textAlign = "left",
    margins = ReaderMargins(top = 24, bottom = 24, left = 16, right = 16),
    paragraphIndent = 1f,
    paragraphSpacing = 0.6f,
    fontFamily = "",
    followSystemTheme = false,
    backgroundColor = "#101010",
    textColor = "#eeeeee",
    keepScreenOn = false,
    orientation = 0,
    resolvedOrientation = 0,
    ttsScrollToTop = false,
    ttsHighlight = true,
    ttsHighlightStyle = TtsHighlightStyle.BACKGROUND,
    ttsHighlightColor = 0xFFFFD54F.toInt(),
    ttsHighlightTextColor = 0xFF1A1A1A.toInt(),
    ttsKeepInView = true,
    bionicReading = false,
    tapZones = NovelTapZones(NovelTapLayout.THIRDS, TappingInvertMode.NONE, 12),
    swipeGestures = true,
    showProgressPercentage = false,
    autoScroll = false,
    autoScrollSpeed = 1f,
    useVolumeButtons = false,
    volumeButtonsInverted = false,
    volumeButtonsFraction = 0.75f,
    railHeightPercent = 60,
    railOnLeft = false,
    useRail = true,
    alwaysShowChapterTransition = true,
)
