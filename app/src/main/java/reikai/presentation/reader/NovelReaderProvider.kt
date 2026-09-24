package reikai.presentation.reader

import android.content.Context
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import reikai.data.coil.extractCoverColor
import reikai.data.coil.seedColor
import reikai.data.novel.tts.NovelTtsSession
import reikai.data.novel.tts.SleepTimer
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.reader.ChapterProgress
import reikai.novel.font.NovelFontManager
import reikai.novel.network.NovelImageRequests
import reikai.presentation.reader.text.NovelWindowDiff
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.system.logcat

/**
 * The light-novel half of the reader's provider seam, over the live [NovelReaderViewModel] the host
 * resolved. The twin of `MangaReaderProvider`, and thin for the same reason: everything that decides
 * behaviour belongs to the model, so this only adapts.
 */
class NovelReaderProvider(
    val viewModel: NovelReaderViewModel,
    private val novelPreferences: NovelPreferences,
    private val fontManager: NovelFontManager,
    private val imageRequests: NovelImageRequests,
    private val titleWords: ChapterTitleWords,
) : ReaderProvider {

    override val chrome: Flow<ReaderChromeState> = combine(
        viewModel.entryTitle,
        viewModel.chapter,
        novelPreferences.readerChapterTitleFormat().changes(),
    ) { title, chapter, format ->
        ReaderChromeState(title, chapter?.let { format.chapterTitle(it.title, it.chapterNumber, titleWords) })
    }

    override val bottomButtons: Flow<List<ReaderBottomButton>> =
        ReaderBottomButton.orderedChanges(ReaderBottomButton.BarPreferences.novel(novelPreferences))

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

    override val fullscreen = novelPreferences.readerFullscreen()

    override val drawUnderCutout = novelPreferences.readerDrawUnderCutout()

    override fun onReaderMoved() = viewModel.readerMoved()

    override fun flushPosition() = viewModel.flushProgress()

    // A novel source has no numeric id, so the browser opens without its headers; the ids let a source
    // that takes pages save the chapter's text from it.
    override suspend fun chapterWebViewIntent(context: Context, url: String, title: String?, chapterId: Long): Intent =
        viewModel.novelIdOf(chapterId)?.let { novelId ->
            WebViewActivity.newNovelChapterIntent(context, url, title, novelId, chapterId)
        } ?: WebViewActivity.newIntent(context, url, title = title)

    override suspend fun chapterWebUrl(chapterId: Long): String? = viewModel.webUrlOf(chapterId)

    // By source and url rather than row id, since that is what the novel screen is pushed with.
    override fun detailsIntent(context: Context): Intent? = viewModel.detailsRoute.value?.let { route ->
        Intent(context, MainActivity::class.java).apply {
            action = Constants.SHORTCUT_NOVEL
            putExtra(Constants.NOVEL_SOURCE_EXTRA, route.source)
            putExtra(Constants.NOVEL_URL_EXTRA, route.url)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    override fun pageBackground(context: Context): Flow<Int> = viewModel.settings.map {
        readerBackgroundColorInt(it.resolvedForSystemTheme(context.isNightMode()).backgroundColor)
    }

    // Hundredths, because that is the unit the stored progress is in.
    override val navigator: Flow<ReaderNavigatorState> = combine(
        viewModel.progressPercent,
        viewModel.settings,
        viewModel.chapterNeighbours,
        novelPreferences.readerShowNavigator().changes(),
    ) { percent, settings, neighbours, show ->
        ReaderNavigatorState(
            progress = ChapterProgress.Percent(percent * 100L),
            shape = when {
                !show -> ReaderNavigatorShape.None
                settings.useRail -> ReaderNavigatorShape.Rail
                else -> ReaderNavigatorShape.Slider
            },
            railOnLeft = settings.railOnLeft,
            railHeightPercent = settings.railHeightPercent,
            hasPrevious = neighbours.previous != null,
            hasNext = neighbours.next != null,
        )
    }

    override val showProgress: Flow<Boolean> = viewModel.settings.map { it.showProgressPercentage }

    override val loadState: Flow<ReaderLoadState> = viewModel.loadState

    override fun retryLoad() = viewModel.retryLoad()

    override fun reloadChapter(fromSource: Boolean) = viewModel.reloadChapter(fromSource)

    override val bookmarked: Flow<Boolean> = viewModel.bookmarked

    override fun toggleBookmark() = viewModel.toggleBookmark()

    override val webUrl: Flow<String?> = viewModel.chapter.map { chapter -> chapter?.let { viewModel.webUrlFor(it) } }

    override suspend fun updateHistory() = viewModel.updateHistory()

    override fun restartReadTimer() = viewModel.restartReadTimer()

    override fun onActivityFinish() = viewModel.onActivityFinish()

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
     * A change to a setting [createViewport] reads once, after the one it was built with. The sheet reaches
     * all four, so the host rebuilds the viewport around the live session, as a rotation does.
     */
    private val viewportRebuilds: Flow<Unit> = listOf<Flow<Any>>(
        novelPreferences.readerRenderingMode().changes(),
        novelPreferences.readerTextSelectable().changes(),
        novelPreferences.readerUseOriginalFonts().changes(),
        novelPreferences.readerSourceCssPriority().changes(),
    )
        .merge()
        .map { viewportSnapshot() }
        .distinctUntilChanged()
        .drop(1)
        .map { }

    private fun viewportSnapshot(): List<Any> = listOf(
        novelPreferences.readerRenderingMode().get(),
        novelPreferences.readerTextSelectable().get(),
        novelPreferences.readerUseOriginalFonts().get(),
        novelPreferences.readerSourceCssPriority().get(),
    )

    /**
     * Text-selectability, the rendering mode and the two WebView font settings are read once here, because
     * the viewport takes them as plain values so it can be built without the graph; a change rebuilds it
     * through [viewportRebuilds]. The volume keys are live without one: the switch is read each press and
     * the rest arrives with every settings push.
     */
    override fun createViewport(host: ReaderActivity): ReaderViewport {
        val textSelectable = novelPreferences.readerTextSelectable().get()
        // One rule for both renderers: the keys are the reader's only while the menu is down, as they
        // are for manga. Read from the host each press, since the menu opens and closes mid-session.
        val volumeKeysActive = { viewModel.settings.value.useVolumeButtons && !host.isMenuVisible }
        if (novelPreferences.readerRenderingMode().get() == NovelRenderingMode.NATIVE) {
            return NovelTextViewport(
                context = host,
                fontManager = fontManager,
                textSelectable = textSelectable,
                volumeKeysActive = volumeKeysActive,
                onProgressChanged = viewModel::reportProgress,
                onProgressSettled = viewModel::saveProgress,
                onTopLine = viewModel::reportTopLine,
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
            fontManager = fontManager,
            imageRequests = imageRequests,
            textSelectable = textSelectable,
            volumeKeysActive = volumeKeysActive,
            useOriginalFonts = novelPreferences.readerUseOriginalFonts().get(),
            sourceCssPriority = novelPreferences.readerSourceCssPriority().get(),
            devTools = novelPreferences.readerWebViewDevTools().get(),
            // Both persist: the live percent debounced, since an auto-scrolled or scrubbed read never
            // settles, and the settled one at once. Either one finishing a chapter marks it read.
            onProgressChanged = viewModel::reportProgress,
            onProgressSettled = viewModel::saveProgress,
            onTopLine = viewModel::reportTopLine,
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

    // Built here rather than from the host's manga collector, which is what updateViewer hangs off and
    // which never fires without a Manga in state.
    override fun attach(host: ReaderActivity) {
        // The session and its position outlive the Activity, so a rebuild lands where the reader is.
        viewportRebuilds.onEach { host.recreate() }.launchIn(host.lifecycleScope)
        val viewport = createViewport(host)
        host.showViewport(viewport)
        // Asked for rather than cast: which text renderer is running is the provider's choice.
        // A novel viewport that does not answer would render an empty reader in silence, so say so.
        when (viewport) {
            is TextViewport -> {
                // Before the first load, which is what reports the renderer landing to read-aloud.
                viewModel.readAloud.attach(viewport.readAloud)
                loadChapters(host, viewport)
            }
            else -> logcat(LogPriority.ERROR) { "Novel viewport renders no text: ${viewport::class}" }
        }
        // Manga locks the window from updateViewer, deferred behind the shared-element transition; a
        // novel launch runs neither, so it follows its own resolved orientation from here.
        viewModel.settings
            .map { it.resolvedOrientation }
            .distinctUntilChanged()
            .onEach(host::setOrientation)
            .launchIn(host.lifecycleScope)
    }

    // Speech outlives the Activity, so the renderer it marks is let go of before it is destroyed.
    override fun detach(viewport: ReaderViewport) {
        (viewport as? TextViewport)?.let { viewModel.readAloud.detach(it.readAloud) }
    }

    /**
     * Hands each chapter the model loads to the installed text renderer, for as long as [host] lives. How
     * that becomes pixels is the viewport's business; the theme is resolved here, because "Auto" reads
     * the host's own night mode.
     */
    private fun loadChapters(host: ReaderActivity, viewport: TextViewport) {
        val scope = host.lifecycleScope
        // "Auto" resolves to a preset here; the stored colours are only what a manual choice left
        // behind, so a document built from them would show the wrong shade.
        val resolvedSettings = viewModel.settings.map { it.resolvedForSystemTheme(host.isNightMode()) }
        // One collector for the load and the window verbs, so an arriving neighbour can never overtake
        // the open that invalidated it.
        val window = viewport.window
        var rendered = emptyList<Long>()
        // Starts unseen, so an Activity rebuilt around a live session renders its window again, at
        // where the reader is rather than where the chapter was opened (landingOf).
        var renderedGeneration = -1
        viewModel.window
            .filter { it.chapters.isNotEmpty() }
            .onEach { state ->
                val settings = resolvedSettings.first()
                val opened = state.generation != renderedGeneration
                if (opened) {
                    renderedGeneration = state.generation
                    val anchor = viewModel.landingOf(state)
                    viewport.load(anchor, settings)
                    // The renderer has let go of the window it had, so its reports count again.
                    viewModel.rendererLanded(state.generation)
                    viewModel.readAloud.onRendererLanded(anchor.chapterId)
                    rendered = listOf(anchor.chapterId)
                }
                val wanted = state.chapters.map { it.chapterId }
                val byId = state.chapters.associateBy { it.chapterId }
                NovelWindowDiff.plan(rendered, wanted).forEach { step ->
                    when (step) {
                        is NovelWindowDiff.Step.Evict -> window.evict(step.chapterId)
                        is NovelWindowDiff.Step.Append -> window.append(byId.getValue(step.chapterId))
                        is NovelWindowDiff.Step.Prepend -> window.prepend(byId.getValue(step.chapterId))
                    }
                }
                rendered = wanted
                // After the verbs, since the chapter being read aloud may be one they just added.
                viewModel.readAloud.onWindowChanged()
                // After the verbs, so an edge is never marked failed on a window that is one append
                // away from reaching past it.
                window.setBoundaryFailures(state.failedPrevious, state.failedNext)
            }
            .launchIn(scope)

        // Changing a display setting reflows the open chapter in place. The first emission is what the
        // document was just built with, so it is dropped rather than pushed straight back.
        resolvedSettings
            .distinctUntilChanged()
            .drop(1)
            .onEach(viewport::applySettings)
            .launchIn(scope)

        // Auto-scroll pauses while the chrome is showing and while the reader is off screen, both of
        // which are the host's own state rather than settings, so the decision is made once here and
        // each renderer only starts and stops. Off screen matters because the loop is not lifecycle
        // aware: left running it advances the chapter behind whatever the reader is looking at. Speech
        // pauses it too, since read-aloud keeps its own paragraph in view and the two would fight.
        combine(
            resolvedSettings,
            host.menuVisibility,
            host.isOnScreen,
            host.engine.readAloudState.map { it.playback == TtsPlayback.Playing },
        ) { settings, menuVisible, onScreen, speaking ->
            (settings.autoScroll && !menuVisible && onScreen && !speaking) to settings.autoScrollSpeed
        }
            .distinctUntilChanged()
            .onEach { (running, speed) -> viewport.setAutoScroll(running, speed) }
            .launchIn(scope)
    }
}
