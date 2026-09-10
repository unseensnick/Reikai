package reikai.presentation.reader

import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.novel.reader.ReaderMargins

/**
 * A plain settings value for the WebView-mode tests, so the two that build a real document do not
 * each carry the constructor. Nothing here is the subject of a test; a case that cares about a field
 * copies this and says so.
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
    ttsEnabled = false,
    ttsRate = 1f,
    ttsPitch = 1f,
    ttsAutoPageAdvance = false,
    ttsScrollToTop = false,
    bionicReading = false,
    removeExtraSpacing = false,
    tapToScroll = true,
    swipeGestures = true,
    showProgressPercentage = false,
    autoScroll = false,
    autoScrollSpeed = 1f,
    useVolumeButtons = false,
    volumeButtonsInverted = false,
    volumeButtonsFraction = 0.75f,
    railHeightPercent = 60,
    railOnLeft = false,
)
