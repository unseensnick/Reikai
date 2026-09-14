package reikai.presentation.reader

import android.content.Context
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import reikai.data.coil.extractCoverColor
import reikai.data.coil.seedColor
import reikai.data.novel.tts.NovelTtsSession
import reikai.data.novel.tts.SleepTimer
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.reader.ChapterProgress

/**
 * The light-novel half of the reader's provider seam, over the live [NovelReaderViewModel] the host
 * resolved. The twin of `MangaReaderProvider`, and thin for the same reason: everything that decides
 * behaviour belongs to the model, so this only adapts.
 */
class NovelReaderProvider(
    val viewModel: NovelReaderViewModel,
    private val novelPreferences: NovelPreferences,
) : ReaderProvider {

    override val chrome: Flow<ReaderChromeState> = combine(
        viewModel.entryTitle,
        viewModel.chapter,
        novelPreferences.readerChapterTitleFormat().changes(),
    ) { title, chapter, format ->
        val chapterTitle = chapter?.let {
            format.chapterTitle(
                it.title,
                it.chapterNumber,
                viewModel::numberedChapterTitle,
                viewModel::numberedChapterTitle,
            )
        }
        ReaderChromeState(title, chapterTitle)
    }

    override val bottomButtons: Flow<List<ReaderBottomButton>> = ReaderBottomButton.orderedChanges(
        novelPreferences.readerBottomButtons(),
        novelPreferences.readerBottomButtonOrder(),
        ReaderBottomButton.Scope.Novel,
    )

    override val bottomButtonScope = ReaderBottomButton.Scope.Novel

    override fun seedColor(context: Context): Flow<Int?> = viewModel.cover
        .filterNotNull()
        .map { cover -> EntryId.Novel(cover.novelId).seedColor { context.extractCoverColor(cover) } }

    override val displayFilters = ReaderDisplayFilters(
        customBrightness = novelPreferences.readerCustomBrightness(),
        customBrightnessValue = novelPreferences.readerCustomBrightnessValue(),
        colorFilter = novelPreferences.readerColorFilter(),
        colorFilterValue = novelPreferences.readerColorFilterValue(),
        colorFilterMode = novelPreferences.readerColorFilterMode(),
        grayscale = novelPreferences.readerGrayscale(),
        invertedColors = novelPreferences.readerInvertedColors(),
    )

    // Hundredths, because that is the unit the stored progress is in.
    override val navigator: Flow<ReaderNavigatorState> = combine(
        viewModel.progressPercent,
        viewModel.settings,
        viewModel.chapterNeighbours,
    ) { percent, settings, neighbours ->
        ReaderNavigatorState(
            progress = ChapterProgress.Percent(percent * 100L),
            useRail = settings.useRail,
            railOnLeft = settings.railOnLeft,
            railHeightPercent = settings.railHeightPercent,
            hasPrevious = neighbours.previous != null,
            hasNext = neighbours.next != null,
        )
    }

    override val showProgress: Flow<Boolean> = viewModel.settings.map { it.showProgressPercentage }

    override val loadState: Flow<ReaderLoadState> = viewModel.loadState

    override fun retryLoad() = viewModel.retryLoad()

    override val bookmarked: Flow<Boolean> = viewModel.bookmarked

    override fun toggleBookmark() = viewModel.toggleBookmark()

    override val webUrl: Flow<String?> = viewModel.chapter.map { it?.let(viewModel::webUrlFor) }

    override suspend fun previousChapter() = viewModel.previousChapter()

    override suspend fun nextChapter() = viewModel.nextChapter()

    override val chapterList: ReaderChapterList = object : ReaderChapterList {
        override val rows: Flow<List<ReaderChapterRow>> = viewModel.chapterRows

        override val currentChapterId: Flow<Long> = viewModel.chapter.map { it?.chapterId ?: -1L }

        override fun open(chapterId: Long) = viewModel.open(chapterId)

        override fun setRead(chapterId: Long, read: Boolean) = viewModel.setChapterRead(chapterId, read)

        override fun setBookmark(chapterId: Long, bookmarked: Boolean) =
            viewModel.setChapterBookmark(chapterId, bookmarked)

        override fun download(chapterId: Long, action: ChapterDownloadAction) =
            viewModel.downloadChapter(chapterId, action)
    }

    override val textSettings: ReaderTextSettings = object : ReaderTextSettings {
        override val state: Flow<ReaderTextState> = viewModel.settings.map {
            // The stored colour, not the resolved one: the picker marks what was chosen, and "Auto"
            // is a choice of its own rather than whichever preset it resolves to right now.
            ReaderTextState(it.fontSize, it.followSystemTheme, it.backgroundColor)
        }

        override fun setFontSize(size: Int) = viewModel.setFontSize(size)

        override fun followSystemTheme() = viewModel.setFollowSystemTheme()

        override fun setThemeColors(background: String, textColor: String) =
            viewModel.setThemeColors(background, textColor)
    }

    override val autoScroll: ReaderAutoScroll = object : ReaderAutoScroll {
        override val enabled: Flow<Boolean> = viewModel.settings.map { it.autoScroll }

        override fun toggle() = viewModel.setAutoScroll(!viewModel.settings.value.autoScroll)

        override fun stop() {
            if (viewModel.settings.value.autoScroll) viewModel.setAutoScroll(false)
        }
    }

    override val bionicReading: ReaderBionicReading = object : ReaderBionicReading {
        override val enabled: Flow<Boolean> = viewModel.settings.map { it.bionicReading }

        override fun toggle() = viewModel.setBionicReading(!viewModel.settings.value.bionicReading)
    }

    override val readAloud: ReaderReadAloud = object : ReaderReadAloud {
        private val controller = viewModel.readAloud
        private val controlsVisible = novelPreferences.readerTtsControlsVisible()
        private val sleepTimer = NovelTtsSession.sleepTimer

        override val state: Flow<ReaderReadAloudState> = combine(
            controller.state,
            controlsVisible.changes(),
            sleepTimer.timer,
        ) { speech, visible, timer -> ReaderReadAloudState(speech.playback, visible, timer) }

        override fun play() = controller.play()

        override fun pause() = controller.pause()

        override fun stop() = controller.stop()

        override fun readFromHere() = controller.readFromHere()

        override fun previousParagraph() = controller.previousParagraph()

        override fun nextParagraph() = controller.nextParagraph()

        override fun toggleControls() = controlsVisible.set(!controlsVisible.get())

        override fun setSleepTimer(minutes: Int) = sleepTimer.setMinutes(minutes)

        override fun setSleepTimerEndOfChapter() = sleepTimer.setEndOfChapter()

        override fun clearSleepTimer() = sleepTimer.clear()

        override fun minutesLeft(timer: SleepTimer.At) = sleepTimer.minutesLeft(timer)
    }

    override val orientation: Flow<Int> = viewModel.settings.map { it.orientation }

    override val keepScreenOn: Flow<Boolean> = viewModel.settings.map { it.keepScreenOn }

    override fun setOrientation(flagValue: Int) = viewModel.setOrientation(flagValue)

    override fun setKeepScreenOn(enabled: Boolean) = viewModel.setKeepScreenOn(enabled)

    /**
     * Text-selectability, the rendering mode and the two WebView font settings are read once here, because
     * the viewport takes them as plain values so it can be built without the graph. Nothing rebuilds a
     * novel viewport mid-session, so a change lands on the next open. That is only acceptable while none
     * of them is reachable from the in-reader sheet: text-selectability would also need a re-bind, since
     * it decides which of the two tap owners is installed. The volume keys are live, because the sheet
     * does reach them: the switch is read each press and the rest arrives with every settings push.
     */
    override fun createViewport(host: ReaderActivity): ReaderViewport {
        val textSelectable = novelPreferences.readerTextSelectable().get()
        // One rule for both renderers: the keys are the reader's only while the menu is down, as they
        // are for manga. Read from the host each press, since the menu opens and closes mid-session.
        val volumeKeysActive = { viewModel.settings.value.useVolumeButtons && !host.isMenuVisible }
        if (novelPreferences.readerRenderingMode().get() == NovelRenderingMode.NATIVE) {
            return NovelTextViewport(
                context = host,
                textSelectable = textSelectable,
                volumeKeysActive = volumeKeysActive,
                onProgressChanged = viewModel::reportProgress,
                onProgressSettled = viewModel::saveProgress,
                onToggleMenu = host::toggleMenu,
                onStepChapter = { forward ->
                    if (forward) host.engine.nextChapter() else host.engine.previousChapter()
                },
                onVisibleChapter = viewModel::reportVisibleChapter,
                onRetryBoundary = viewModel::retryBoundary,
                cutoutTopDp = host::displayCutoutTopDp,
                onChapterFits = viewModel::reportFitsOnScreen,
                onChapterEndSeen = viewModel::reportChapterEndSeen,
            )
        }
        return NovelWebViewport(
            context = host,
            textSelectable = textSelectable,
            volumeKeysActive = volumeKeysActive,
            useOriginalFonts = novelPreferences.readerUseOriginalFonts().get(),
            sourceCssPriority = novelPreferences.readerSourceCssPriority().get(),
            devTools = novelPreferences.readerWebViewDevTools().get(),
            // Both persist: the live percent debounced, since an auto-scrolled or scrubbed read never
            // settles, and the settled one at once. Either one finishing a chapter marks it read.
            onProgressChanged = viewModel::reportProgress,
            onProgressSettled = viewModel::saveProgress,
            // Taken from the host being built against rather than held, so a reader rebuilt after a
            // rotation toggles the live Activity's menu instead of the destroyed one's.
            onToggleMenu = host::toggleMenu,
            // Through the engine rather than the model, so a swipe is sequenced with the viewport the
            // same way the bar's step buttons are.
            onStepChapter = { forward -> if (forward) host.engine.nextChapter() else host.engine.previousChapter() },
            onVisibleChapter = viewModel::reportVisibleChapter,
            onRetryBoundary = viewModel::retryBoundary,
            cutoutTopDp = host::displayCutoutTopDp,
            onChapterFits = viewModel::reportFitsOnScreen,
            onChapterEndSeen = viewModel::reportChapterEndSeen,
        )
    }
}
