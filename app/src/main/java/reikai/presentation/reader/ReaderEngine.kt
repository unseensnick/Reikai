package reikai.presentation.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.seekTo
import kotlin.time.Duration.Companion.seconds

/**
 * The Reikai-owned reader engine, above one provider per content type. It owns dialog dispatch and
 * the viewport slot, sequences chapter steps and picks, and routes seeking to the viewport; the
 * provider performs the load and reports position. Menu visibility belongs to the host, because half
 * of it is the insets controller.
 */
@AssistedInject
class ReaderEngine(
    // Assisted: the provider wraps a model the host has already resolved, so it can only be built at
    // the call site. Public because the host builds its viewport through it, which is what keeps the
    // host from branching on content type once there is a second provider.
    @Assisted val provider: ReaderProvider,
    private val uiPreferences: UiPreferences,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(provider: ReaderProvider): ReaderEngine
    }

    // Session state, shared here rather than in the provider: this engine outlives an Activity
    // recreation, so state kept alive by the Activity's scope would freeze the first time the reader
    // is rotated. Eager, because the window's orientation and keep-screen-on follow these whether or
    // not anything is composed.

    /** What the chrome shows, answered by whichever content type this session is for. */
    val chrome: StateFlow<ReaderChromeState> =
        provider.chrome.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderChromeState())

    /** The bottom-bar buttons this session offers, likewise its own rather than manga's. */
    val bottomButtons: StateFlow<List<ReaderBottomButton>> =
        provider.bottomButtons.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Where the reader is in the chapter, and which navigator shows it. */
    val navigator: StateFlow<ReaderNavigatorState> =
        provider.navigator.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderNavigatorState())

    /** Whether this session wants a progress readout while the chrome is hidden. */
    val showProgress: StateFlow<Boolean> =
        provider.showProgress.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether a chapter is loading or failed, which this raises as a dialog for the host to show. */
    private val loadState: StateFlow<ReaderLoadState> =
        provider.loadState.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderLoadState.Idle)

    /** The chapter on screen, -1 before one opens, which is what a new launch is compared against. */
    val currentChapterId: StateFlow<Long> =
        provider.chapterList.currentChapterId.stateIn(viewModelScope, SharingStarted.Eagerly, -1L)

    /**
     * A pick that failed is retried as a pick, so the chapter still lands where it resumes. Only while
     * its failure is the latest: a load that failed since is what the raised failure is about.
     */
    fun retryLoad() {
        val pick = failedPick?.takeIf { it.attempt == latestFailure }
        if (pick != null) openChapter(pick.chapterId) else provider.retryLoad()
    }

    /** The colour the chrome tints with, from the entry's cover, while cover-based theming is on. */
    fun coverSeed(context: Context): Flow<Int?> =
        if (uiPreferences.themeCoverBased.get()) provider.seedColor(context) else flowOf(null)

    /** Whether the open chapter is bookmarked, so the bar's control reflects this session's chapter. */
    val bookmarked: StateFlow<Boolean> =
        provider.bookmarked.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleBookmark() = provider.toggleBookmark()

    fun reloadChapter(fromSource: Boolean) = provider.reloadChapter(fromSource)

    /** The open chapter's page on the source site, null where it has none. */
    val webUrl: StateFlow<String?> =
        provider.webUrl.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Scrubbing goes to the viewport rather than the provider, since only it can move the reader.
     *  A continuous auto-scroll is held a moment first, so the reader sees where the scrub landed. */
    fun seek(progress: ChapterProgress) {
        holdForSeek()
        viewport.value?.seekTo(progress)
    }

    /** Back to the open chapter's start, in whatever unit its medium counts in. */
    fun seekToStart() {
        navigator.value.progress?.let { seek(it.seekTo(0f)) }
    }

    // The step and the viewer's response to it are sequenced here, because the provider knows which
    // chapter is next and only the viewport can act on having arrived.

    /**
     * The chapter sheet's rows and verbs, for whichever content type this session is. Opening is the
     * one verb the engine wraps: the provider only starts the load, and something has to land the
     * viewport on the chapter once it arrives.
     */
    val chapterList: ReaderChapterList = object : ReaderChapterList by provider.chapterList {
        override fun open(chapterId: Long) = openChapter(chapterId)
    }

    /** One pick at a time: a chapter that never loads would otherwise leave a wait behind per tap. */
    private var openJob: Job? = null

    private class FailedPick(val chapterId: Long, val attempt: Long)

    /** The last pick, while its load has failed and nothing has been picked since. */
    private var failedPick: FailedPick? = null

    /** The attempt of the latest failure raised, which is the one a Retry answers. */
    private var latestFailure: Long? = null

    private fun openChapter(chapterId: Long) {
        openJob?.cancel()
        failedPick = null
        openJob = viewModelScope.launch {
            // A failure already showing is an earlier load's, so only a later one counts. Told apart
            // by attempt: the Loading between the two can be conflated away, and the messages match.
            val earlier = (provider.loadState.first() as? ReaderLoadState.Failed)?.attempt
            provider.chapterList.open(chapterId)
            // A failure ends the pick, or reaching that chapter later by a step would land it like one.
            val landed: Flow<ReaderLoadState.Failed?> =
                provider.chapterList.currentChapterId.filter { it == chapterId }.map { null }
            val failure = merge(
                landed,
                provider.loadState.filterIsInstance<ReaderLoadState.Failed>().filter { it.attempt != earlier },
            ).first()
            if (failure != null) {
                failedPick = FailedPick(chapterId, failure.attempt)
                return@launch
            }
            // Yielded, because the host hands the new chapters to the viewer from its own collector on
            // the same state update: a move issued before that delivery looks for a page the viewer
            // does not hold yet and is silently dropped.
            yield()
            viewport.value?.onChapterOpened()
        }
    }

    /** Typography, or null where this session's pages are images. */
    val textSettings: ReaderTextSettings? get() = provider.textSettings

    /**
     * Whether auto-scroll is running now, which the bar and the sheet flip. Seeded once from the
     * session's start-on-open setting and never written back, so the engine outliving a rotation
     * keeps it as the reader left it.
     */
    val autoScrollRunning: StateFlow<Boolean>
        field = MutableStateFlow(provider.autoScrollOnOpen.get())

    fun toggleAutoScroll() {
        autoScrollRunning.value = !autoScrollRunning.value
    }

    // What pauses it, none of which stops it: each clears on its own and the scroll carries on.
    private data class AutoScrollPause(
        val menuVisible: Boolean = false,
        val onScreen: Boolean = true,
        val touching: Boolean = false,
    )

    private val autoScrollPause = MutableStateFlow(AutoScrollPause())

    fun setMenuVisible(visible: Boolean) = autoScrollPause.update { it.copy(menuVisible = visible) }

    /** Off screen pauses it, since nothing else stops a frame loop running behind another app. */
    fun setOnScreen(onScreen: Boolean) = autoScrollPause.update { it.copy(onScreen = onScreen) }

    /** A finger on the screen pauses it, so a drag is never fought and a stepped countdown restarts. */
    fun setTouching(touching: Boolean) = autoScrollPause.update { it.copy(touching = touching) }

    private val seekHeld = MutableStateFlow(false)
    private var seekHoldJob: Job? = null

    private fun holdForSeek() {
        seekHoldJob?.cancel()
        seekHeld.value = true
        seekHoldJob = viewModelScope.launch {
            delay(SEEK_HOLD)
            seekHeld.value = false
        }
    }

    /** Bionic reading, or null where this session's pages are images. */
    val bionicReading: ReaderBionicReading? get() = provider.bionicReading

    val bionicReadingEnabled: StateFlow<Boolean> =
        (provider.bionicReading?.enabled ?: flowOf(false))
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Read-aloud, or null where this session's pages are images. */
    val readAloud: ReaderReadAloud? get() = provider.readAloud

    /** Eager like the rest, because auto-scroll pauses off it whether or not the bar is composed. */
    val readAloudState: StateFlow<ReaderReadAloudState> =
        (provider.readAloud?.state ?: flowOf(ReaderReadAloudState()))
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderReadAloudState())

    fun previousChapter() = stepChapter { provider.previousChapter() }

    fun nextChapter() = stepChapter { provider.nextChapter() }

    private fun stepChapter(step: suspend () -> Boolean) {
        viewModelScope.launch {
            if (step()) viewport.value?.onChapterStepped()
        }
    }

    // The bar's own verbs, so a button acts on the entry that is open rather than on a manga model
    // the session may not have.

    val orientation: StateFlow<Int> =
        provider.orientation.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderOrientation.DEFAULT.flagValue)

    fun setOrientation(flagValue: Int) = provider.setOrientation(flagValue)

    val keepScreenOn: StateFlow<Boolean> =
        provider.keepScreenOn.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setKeepScreenOn(enabled: Boolean) = provider.setKeepScreenOn(enabled)

    // Dialogs: one slot, so raising a dialog is also how the previous one closes.

    private val mutableDialog = MutableStateFlow<ReaderDialog?>(null)
    val dialog: StateFlow<ReaderDialog?> = mutableDialog.asStateFlow()

    fun openDialog(dialog: ReaderDialog) {
        mutableDialog.value = dialog
    }

    fun dismissDialog() {
        mutableDialog.value = null
    }

    // Raises the load surface as the load moves, from the engine's own scope. A failure is a one-shot
    // over state that stays Failed until the next load, so the host raising it from a collector of its
    // own reopened a dismissed failure on every Activity recreation: a rotation, a fold, a dark-mode
    // switch. Here it is raised once per failure and dismissing it sticks. The reader stays open on a
    // failure, unlike the initial-load error the host closes on.
    init {
        viewModelScope.launch {
            loadState.collect { state ->
                when (state) {
                    // Not over the settings sheet: a setting changed there reloads the chapter in place, and
                    // replacing the sheet would close it under the reader mid-change.
                    ReaderLoadState.Loading -> if (mutableDialog.value != ReaderDialog.Settings) {
                        openDialog(ReaderDialog.Loading)
                    }
                    is ReaderLoadState.Failed -> {
                        latestFailure = state.attempt
                        // The failed chapter's own page, where a site challenge is solved or, for a
                        // source that takes pages, the chapter saved from.
                        val webUrl = state.chapterId?.let { provider.chapterWebUrl(it) }
                        openDialog(
                            ReaderDialog.LoadFailed(state.message, state.canKeepReading, webUrl, state.chapterId),
                        )
                    }
                    // Only what this raised: a chapter arriving must not close the sheet the reader
                    // opened while waiting for it.
                    ReaderLoadState.Idle -> dismissLoadDialog()
                }
            }
        }
    }

    private fun dismissLoadDialog() {
        val raised = mutableDialog.value
        if (raised is ReaderDialog.Loading || raised is ReaderDialog.LoadFailed) dismissDialog()
    }

    // Viewport: what is currently rendering the entry, whatever content type it is.

    private val mutableViewport = MutableStateFlow<ReaderViewport?>(null)
    val viewport: StateFlow<ReaderViewport?> = mutableViewport.asStateFlow()

    /**
     * Swaps in the viewport the host just built. Destroying the outgoing one happens here rather
     * than at the call site, because losing that step leaks the whole previous view tree and nothing
     * would fail loudly.
     *
     * Building is separate because only the host can supply itself to a viewer constructor, and
     * folding that in here would mean nothing could exercise the swap without inventing a host.
     */
    fun installViewport(viewport: ReaderViewport) {
        mutableViewport.value?.let(::release)
        mutableViewport.value = viewport
    }

    /** Called when the host goes away, since the viewport holds its view tree. */
    fun destroyViewport() {
        mutableViewport.value?.let(::release)
        mutableViewport.value = null
    }

    private fun release(viewport: ReaderViewport) {
        provider.detach(viewport)
        viewport.destroy()
    }

    // Auto-scroll's one driver, below the viewport slot it reads, since an init block runs in declaration
    // order. A new viewport, a pause or a stop cancels the running drive, which is what restarts a
    // stepped countdown from full and sends a continuous one its 0. Any dialog in the slot pauses it
    // too: the page sheet opens with the menu hidden and the settings sheet can hide it.
    init {
        viewModelScope.launch {
            combine(
                autoScrollRunning,
                mutableViewport,
                combine(autoScrollPause, mutableDialog) { pause, dialog -> pause to (dialog != null) },
                readAloudState,
                seekHeld,
            ) { running, viewport, (pause, dialogOpen), readAloud, held ->
                val shape = if (running) viewport?.autoScroll else null
                val paused = pause.menuVisible || !pause.onScreen || pause.touching || dialogOpen ||
                    readAloud.playback == TtsPlayback.Playing ||
                    (held && shape is ViewportAutoScroll.Continuous)
                AutoScrollDrive(shape, paused)
            }
                .distinctUntilChanged { old, new -> old.shape === new.shape && old.paused == new.paused }
                .collectLatest(::drive)
        }
    }

    private class AutoScrollDrive(val shape: ViewportAutoScroll?, val paused: Boolean)

    private suspend fun drive(drive: AutoScrollDrive) {
        if (drive.paused) return
        when (val shape = drive.shape) {
            null -> Unit
            // Each new page, or the one on screen becoming ready, starts the count over.
            is ViewportAutoScroll.Stepped -> combine(
                shape.shownPage.distinctUntilChanged(),
                shape.intervalSeconds.changes(),
                ::Pair,
            ).collectLatest { (page, seconds) ->
                if (!page.ready) return@collectLatest
                while (true) {
                    delay(seconds.seconds)
                    shape.advance()
                }
            }
            is ViewportAutoScroll.Continuous -> try {
                shape.speed.changes().collect { shape.run(it) }
                awaitCancellation()
            } finally {
                shape.run(0f)
            }
        }
    }

    internal companion object {
        /** How long a scrub holds a continuous scroll, so the reader sees where it landed. */
        val SEEK_HOLD = 2.seconds
    }
}
