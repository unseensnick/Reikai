package eu.kanade.presentation.reader.appbars

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import reikai.presentation.reader.ReaderActionRow
import reikai.presentation.reader.ReaderChapterStep

@Composable
fun ReaderBottomBar(
    // RK -->
    enabledButtons: List<ReaderBottomButton>,
    chapterStep: ReaderChapterStep?,
    // RK <--
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // RK -->
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    keepScreenOn: Boolean,
    onClickKeepScreenOn: () -> Unit,
    onClickTextSize: (() -> Unit)?,
    onClickTheme: (() -> Unit)?,
    onClickScrollToTop: () -> Unit,
    autoScrollActive: Boolean,
    onClickAutoScroll: (() -> Unit)?,
    bionicActive: Boolean,
    onClickBionic: (() -> Unit)?,
    readAloudControlsVisible: Boolean,
    onClickReadAloud: (() -> Unit)?,
    onLongClickReadAloud: () -> Unit,
    // RK <--
    modifier: Modifier = Modifier,
) {
    // RK: the bar is the shared action row, which draws either content type's buttons.
    ReaderActionRow(
        modifier = modifier,
        enabledButtons = enabledButtons,
        chapterStep = chapterStep,
        onClickChapterList = onClickChapterList,
        onClickWebView = onClickWebView,
        onClickBrowser = onClickBrowser,
        onClickShare = onClickShare,
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
        orientation = orientation,
        onClickOrientation = onClickOrientation,
        onClickSettings = onClickSettings,
        readingMode = readingMode,
        onClickReadingMode = onClickReadingMode,
        cropEnabled = cropEnabled,
        onClickCropBorder = onClickCropBorder,
    )
}
