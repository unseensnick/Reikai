package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import reikai.domain.reader.ChapterProgress
import reikai.presentation.reader.ReadAloudControls
import reikai.presentation.reader.ReaderBarsFadeSpec
import reikai.presentation.reader.ReaderBarsSlideSpec
import reikai.presentation.reader.ReaderChapterStep
import reikai.presentation.reader.readerBarEnter
import reikai.presentation.reader.readerBarExit
import reikai.presentation.reader.readerChromeColor
import tachiyomi.presentation.core.components.material.padding

// RK: bar animation specs + scrim color moved to reikai.presentation.reader.ReaderChrome so the manga
// and novel readers share one definition. Values unchanged.

@Composable
fun ReaderAppBars(
    visible: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    chapterNavigatorType: ChapterNavigatorType,
    // RK: which way the chapter buttons point when the bar draws them
    readingRtl: Boolean,
    verticalNavigatorHeight: Float,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    progress: ChapterProgress?,
    onSeek: (ChapterProgress) -> Unit,
    onSeekFinished: () -> Unit,

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // RK -->
    bottomButtons: List<ReaderBottomButton>,
    onClickChapterList: () -> Unit,
    keepScreenOn: Boolean,
    onClickKeepScreenOn: () -> Unit,
    onClickTextSize: (() -> Unit)?,
    onClickTheme: (() -> Unit)?,
    onClickScrollToTop: () -> Unit,
    onEditBottomButtons: () -> Unit,
    autoScrollActive: Boolean,
    onClickAutoScroll: (() -> Unit)?,
    bionicActive: Boolean,
    onClickBionic: (() -> Unit)?,
    // Null where the session cannot read aloud, which keeps both the bar button and the controls away.
    onClickReadAloud: (() -> Unit)?,
    onLongClickReadAloud: () -> Unit,
    readAloudControlsVisible: Boolean,
    readAloudPlaying: Boolean,
    sleepTimerActive: Boolean,
    onReadFromHere: () -> Unit,
    onPreviousParagraph: () -> Unit,
    onPlayPause: () -> Unit,
    onNextParagraph: () -> Unit,
    onClickSleepTimer: () -> Unit,
    // The chrome's inner edges in window pixels, null for an end with nothing showing: the top bar's
    // bottom, and the top of the bottom bar or the read-aloud controls, whichever is higher.
    onCoverChanged: (topBarBottom: Float?, bottomChromeTop: Float?) -> Unit,
    // RK <--
) {
    val backgroundColor = readerChromeColor() // RK: shared scrim (see ReaderChrome)

    // RK --> measured on the bars' slots, which take their full size as they start to enter, so a text
    // renderer gets where they settle rather than every frame of the slide. A slot sliding out is not
    // listened to and reports nothing once it is gone, so hiding clears it here.
    val showing by rememberUpdatedState(visible)
    var topBarBottom by remember { mutableStateOf<Float?>(null) }
    var bottomBarTop by remember { mutableStateOf<Float?>(null) }
    var controlsTop by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(visible) {
        if (!visible) {
            topBarBottom = null
            bottomBarTop = null
        }
    }
    val reportCover by rememberUpdatedState(onCoverChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { topBarBottom to listOfNotNull(bottomBarTop, controlsTop).minOrNull() }
            .collect { (top, bottom) -> reportCover(top, bottom) }
    }
    // RK <--

    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            // RK -->
            modifier = Modifier.onGloballyPositioned {
                if (showing) topBarBottom = it.boundsInWindow().bottom
            },
            // RK <--
            // RK: shared top-bar transition (see ReaderChrome)
            enter = readerBarEnter(fromBottom = false),
            exit = readerBarExit(fromBottom = false),
        ) {
            ReaderTopBar(
                modifier = Modifier
                    .background(backgroundColor)
                    .clickable(onClick = onClickTopAppBar),
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                navigateUp = navigateUp,
                bookmarked = bookmarked,
                onToggleBookmarked = onToggleBookmarked,
                onOpenInWebView = onOpenInWebView,
                onOpenInBrowser = onOpenInBrowser,
                onShare = onShare,
                // RK
                onEditBottomButtons = onEditBottomButtons,
            )
        }

        if (chapterNavigatorType.isVertical()) { // RK: NONE draws no rail
            val sliderOnLeft = chapterNavigatorType == ChapterNavigatorType.VERTICAL_LEFT
            CompositionLocalProvider(
                LocalLayoutDirection provides if (sliderOnLeft) LayoutDirection.Ltr else LayoutDirection.Rtl,
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    AnimatedVisibility(
                        visible = visible,
                        // RK: shared bar animation specs (see ReaderChrome).
                        enter = slideInHorizontally(ReaderBarsSlideSpec) { if (sliderOnLeft) -it else it } +
                            fadeIn(ReaderBarsFadeSpec),
                        exit = slideOutHorizontally(ReaderBarsSlideSpec) { if (sliderOnLeft) -it else it } +
                            fadeOut(ReaderBarsFadeSpec),
                    ) {
                        Row {
                            Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
                            Box(
                                modifier = Modifier.fillMaxHeight(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                ChapterNavigator(
                                    modifier = Modifier.fillMaxHeight(verticalNavigatorHeight),
                                    type = chapterNavigatorType,
                                    onNextChapter = onNextChapter,
                                    enabledNext = enabledNext,
                                    onPreviousChapter = onPreviousChapter,
                                    enabledPrevious = enabledPrevious,
                                    progress = progress,
                                    onSeek = onSeek,
                                    onSeekFinished = onSeekFinished,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        // RK --> the read-aloud controls hang upward from a zero-height anchor on top of the bottom bar,
        // so they sit over the reader without taking layout space from it, and ride down to the screen
        // edge once the bar is gone.
        if (readAloudControlsVisible && onClickReadAloud != null) {
            DisposableEffect(Unit) { onDispose { controlsTop = null } }
            Box(
                modifier = Modifier.fillMaxWidth().height(0.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                ReadAloudControls(
                    playing = readAloudPlaying,
                    sleepTimerActive = sleepTimerActive,
                    onReadFromHere = onReadFromHere,
                    onPreviousParagraph = onPreviousParagraph,
                    onPlayPause = onPlayPause,
                    onNextParagraph = onNextParagraph,
                    onClickSleepTimer = onClickSleepTimer,
                    modifier = Modifier
                        .wrapContentHeight(Alignment.Bottom, unbounded = true)
                        .then(
                            // Without the bar, the system navigation bar and the progress readout along
                            // the bottom edge are what it has to clear.
                            if (visible) {
                                Modifier.padding(bottom = MaterialTheme.padding.small)
                            } else {
                                Modifier
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .padding(bottom = MaterialTheme.padding.large)
                            },
                        )
                        // Last, so the padding below the controls is not counted as covering the text.
                        .onGloballyPositioned { controlsTop = it.boundsInWindow().top },
                )
            }
        }
        // RK <--

        AnimatedVisibility(
            visible = visible,
            // RK -->
            modifier = Modifier.onGloballyPositioned {
                if (showing) bottomBarTop = it.boundsInWindow().top
            },
            // RK <--
            // RK: shared bottom-bar transition (see ReaderChrome)
            enter = readerBarEnter(fromBottom = true),
            exit = readerBarExit(fromBottom = true),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                if (chapterNavigatorType.isHorizontal()) {
                    ChapterNavigator(
                        type = chapterNavigatorType,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        progress = progress,
                        onSeek = onSeek,
                        onSeekFinished = onSeekFinished,
                    )
                }
                ReaderBottomBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .padding(horizontal = MaterialTheme.padding.small)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    // RK -->
                    enabledButtons = bottomButtons,
                    chapterStep = ReaderChapterStep(
                        isRtl = readingRtl,
                        hasPrevious = enabledPrevious,
                        hasNext = enabledNext,
                        onPrevious = onPreviousChapter,
                        onNext = onNextChapter,
                    ).takeIf { chapterNavigatorType == ChapterNavigatorType.NONE },
                    // RK <--
                    readingMode = readingMode,
                    onClickReadingMode = onClickReadingMode,
                    orientation = orientation,
                    onClickOrientation = onClickOrientation,
                    cropEnabled = cropEnabled,
                    onClickCropBorder = onClickCropBorder,
                    onClickSettings = onClickSettings,
                    // RK -->
                    onClickChapterList = onClickChapterList,
                    onClickWebView = onOpenInWebView,
                    onClickBrowser = onOpenInBrowser,
                    onClickShare = onShare,
                    keepScreenOn = keepScreenOn,
                    onClickKeepScreenOn = onClickKeepScreenOn,
                    onClickTextSize = onClickTextSize,
                    onClickTheme = onClickTheme,
                    onClickScrollToTop = onClickScrollToTop,
                    autoScrollActive = autoScrollActive,
                    onClickAutoScroll = onClickAutoScroll,
                    bionicActive = bionicActive,
                    onClickBionic = onClickBionic,
                    readAloudControlsVisible = readAloudControlsVisible,
                    onClickReadAloud = onClickReadAloud,
                    onLongClickReadAloud = onLongClickReadAloud,
                    // RK <--
                )
            }
        }
    }
}
