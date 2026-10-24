package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import reikai.domain.novel.NovelTapLayout
import reikai.domain.novel.NovelTextAlign
import reikai.domain.novel.tts.TtsHighlightStyle
import java.util.concurrent.TimeUnit

/**
 * A plain settings value for the tests that render a real chapter, in the WebView and the native
 * renderer alike, so none of them carries the constructor. Nothing here is the subject of a test; a
 * case that cares about a field copies this and says so, and a field changed here reaches them all.
 */
internal val readerTestSettings = NovelReaderSettings(
    fontSize = 18,
    lineHeight = 1.6f,
    textAlign = NovelTextAlign.LEFT,
    margins = ReaderMargins(top = 24, bottom = 24, left = 16, right = 16),
    paragraphIndent = 1f,
    paragraphSpacing = 0.6f,
    fontFamily = "",
    followSystemTheme = false,
    backgroundColor = "#101010",
    textColor = "#eeeeee",
    keepScreenOn = false,
    orientation = 0,
    ttsScrollToTop = false,
    ttsHighlight = true,
    ttsHighlightStyle = TtsHighlightStyle.BACKGROUND,
    ttsHighlightColor = 0xFFFFD54F.toInt(),
    ttsHighlightTextColor = 0xFF1A1A1A.toInt(),
    ttsKeepInView = true,
    bionicReading = false,
    tapZones = NovelTapZones(NovelTapLayout.THIRDS, TappingInvertMode.NONE, 12),
    swipeGestures = true,
    // Instant, so a test reads a turned page without waiting out a slide; the slide has its own case.
    pageTransitions = false,
    showProgressPercentage = false,
    useVolumeButtons = false,
    volumeButtonsInverted = false,
    volumeButtonsFraction = 0.75f,
    railHeightPercent = 60,
    railOnLeft = false,
    useRail = true,
    alwaysShowChapterTransition = true,
)

/**
 * Runs [block] on the main thread, as a viewport or renderer call must, and fails the case once
 * [MAIN_WAIT_S] passes rather than hanging the run: a load or render that never finishes would
 * otherwise block every case after it. Generous, since it bounds a hang rather than a speed.
 */
internal fun <T> onMain(block: suspend CoroutineScope.() -> T): T = runBlocking(Dispatchers.Main) {
    withTimeout(TimeUnit.SECONDS.toMillis(MAIN_WAIT_S)) { block() }
}

internal const val MAIN_WAIT_S = 30L

/** True once the page reports the bundled Lora face loaded, not just named. */
internal const val LORA_LOADED =
    "[...document.fonts].some(f => f.family.replace(/['\"]/g, '') === 'lora' && f.status === 'loaded')"
