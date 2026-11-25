package reikai.presentation.reader

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.loadFailureMessage
import eu.kanade.tachiyomi.network.interceptor.cloudflareBlockedUrl
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import reikai.data.coil.NovelChapterPictureCache
import reikai.data.novel.tts.SystemTtsEngine
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.download.downloadStateOf
import reikai.domain.download.offersDownload
import reikai.domain.download.rowDownloadChapters
import reikai.domain.download.runChapterAction
import reikai.domain.library.ContentType
import reikai.domain.manga.AdultContentChecker
import reikai.domain.merge.ChapterGap
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.CopyToOpen
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.GroupViewerFlags
import reikai.domain.merge.MergeScope
import reikai.domain.merge.expandToUnits
import reikai.domain.merge.mergeMemberName
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelReadingOrder
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.interactor.DeleteNovelChaptersBehindReader
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.SetNovelCustomCover
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.isOnDevice
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelCover
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.readerOrientation
import reikai.domain.novel.ownersOf
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.reader.ChapterIncognito
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.ChapterRetryCooldown
import reikai.domain.reader.ReadSessionClock
import reikai.domain.reader.ReaderProgressWrites
import reikai.domain.reader.neighbourChapters
import reikai.domain.readinglog.ReadingLogRepository
import reikai.domain.source.SourceKey
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.toDownloadState
import reikai.novel.install.LnPluginInstaller
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.EmptyChapterException
import reikai.novel.source.NovelChapterStylesheet
import reikai.novel.source.NovelChapterText
import reikai.novel.source.NovelChapterTextCache
import reikai.novel.source.NovelChapterTextLoader
import reikai.novel.source.NovelPageFetcher
import reikai.novel.source.NovelSourceManager
import reikai.presentation.components.chapterSubtitle
import reikai.presentation.components.mergeSourceLabels
import reikai.presentation.components.savedPictureFolder
import reikai.presentation.details.novelChapterVolume
import reikai.presentation.reader.text.InFlightLoads
import reikai.presentation.reader.text.NovelChapterFinish
import reikai.presentation.reader.text.NovelLeaveRule
import reikai.presentation.reader.text.NovelOpenLanding
import reikai.presentation.reader.text.NovelResume
import reikai.presentation.reader.text.NovelWindowReach
import reikai.util.runCatchingCancellable
import reikai.util.snapshotOnChange
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The light-novel provider model under the shared reader host: reader display settings, and the raw
 * chapter HTML the viewport renders. Settings live in their own flow so changing one updates the
 * rendered chapter live instead of reloading it.
 */
@AssistedInject
class NovelReaderViewModel(
    @Assisted private val launchNovelId: Long,
    @Assisted val initialChapterId: Long,
    @Assisted private val launchSourceScoped: Boolean,

    /** The host's, so the chapter being read survives a process kill. Assisted like the ids above,
     *  since this model is built through the manual factory rather than off the graph's extras. */
    @Assisted private val savedState: SavedStateHandle,
    /** The launch reopens read aloud where it is (its notification, Continue, History), rather than
     *  asking for a chapter of its own, which stops it. */
    @Assisted private val followSession: Boolean,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val novelPreferences: NovelPreferences,
    private val downloadManagerProvider: () -> NovelDownloadManager,
    private val upsertNovelHistory: UpsertNovelHistory,
    private val novelHistory: NovelHistoryRepository,
    private val readingLog: ReadingLogRepository,
    private val copyMarks: ChapterCopyMarkRepository,
    private val localVolumes: LocalVolumeRepository,
    private val setNovelReadStatus: SetNovelReadStatus,
    private val getIncognitoState: GetIncognitoState,
    private val setNovelViewerFlags: SetNovelViewerFlags,
    private val novelDownloadCache: NovelDownloadCache,
    private val chapterTextCache: NovelChapterTextCache,
    private val deleteChaptersBehindReader: DeleteNovelChaptersBehindReader,
    private val basePreferences: BasePreferences,
    private val context: Context,
    private val pageFetcher: NovelPageFetcher,
    private val getNextNovelChapter: GetNextNovelChapter,
    private val setNovelCustomCover: SetNovelCustomCover,
    private val pictureCache: NovelChapterPictureCache,
    private val readerPreferences: ReaderPreferences,
    private val progressSlot: NovelProgressSlot,
    private val chapterFinish: NovelChapterFinish,
    private val readAloudSession: ReadAloudSession,
    // Dispatchers.IO in the app, which is what launchIO would have used. Passed in so a JVM test can
    // run the whole session on its own scheduler.
    @Assisted private val io: CoroutineDispatcher,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(
            launchNovelId: Long,
            initialChapterId: Long,
            launchSourceScoped: Boolean,
            savedState: SavedStateHandle,
            followSession: Boolean,
            io: CoroutineDispatcher,
        ): NovelReaderViewModel
    }

    /** The novel and scope this reader reads: the launch's, or read aloud's view of the series it follows. */
    private val view = savedState.get<LongArray>(SAVED_VIEW)?.let { (id, scoped) -> id to (scoped == 1L) }
        ?: readAloudSession.state.value.viewFor(launchNovelId, launchSourceScoped, followSession).also { (id, scoped) ->
            // After a kill there is no read aloud to ask again, and the saved chapter belongs to this view.
            savedState[SAVED_VIEW] = longArrayOf(id, if (scoped) 1L else 0L)
        }

    val novelId: Long = view.first

    /** Source scope walks only [novelId]'s own chapters; group scope aggregates the merge group. */
    val sourceScoped: Boolean = view.second

    // Shadow the shared launchIO, which hard-codes Dispatchers.IO: members win over imported extensions,
    // so every launch below runs on [io].
    private fun CoroutineScope.launchIO(block: suspend CoroutineScope.() -> Unit): Job =
        launch(io, block = block)

    // The reader flag writes, in the order they were picked, so the latest pick is what every member keeps:
    // two writes running at once over a merged series' members could leave them disagreeing.
    private val flagWrites = ReaderProgressWrites(appScope)

    // Building the manager restores the persisted queue and can start the download worker, so it is
    // resolved on first read rather than at construction, which keeps that off the opening thread.
    private val downloadManager: NovelDownloadManager get() = downloadManagerProvider()

    /** Session-scoped, so the source cache inside it lives exactly as long as this reading session. */
    private val textLoader = NovelChapterTextLoader(
        context = context,
        novelRepo = novelRepo,
        sourceManager = sourceManager,
        installer = installer,
        preferences = novelPreferences,
        textCache = chapterTextCache,
        readDownloaded = { novel, chapter -> downloadManager.getChapterText(novel, chapter) },
    )

    /** Asked for the owner of each chapter written, since a merged novel's members can differ. */
    private val incognito = ChapterIncognito(
        sourceOf = { owner -> novelRepo.getById(owner)?.source?.let(SourceKey::Novel) },
        isIncognito = getIncognitoState::await,
    )

    /** Whether reading a chapter owned by [ownerId] is recorded, which incognito turns off. */
    suspend fun isRecorded(ownerId: Long): Boolean = !incognito.of(ownerId)

    /** Owning novel of the current chapter. Defaults to the host (== owner for a standalone novel);
     *  a merged session re-points it per chapter so the web actions resolve the source being read. */
    @Volatile
    private var currentNovelId: Long = novelId

    /** When the current chapter began being read, for the novel-history session duration, on the clock
     *  the manga reader shares. Restarted whenever a different chapter loads. */
    private val chapterReadSession = ReadSessionClock()

    /** Whether the reader is on screen: between a pause and a resume only read aloud is reading. */
    @Volatile
    private var onScreen = true

    /** Read aloud as this reader sees it: the app's session while it reads this series, else nothing. */
    val readAloud = ReadAloudLink(
        readAloudSession,
        novelId,
        sourceScoped,
        memberIds = { memberIds },
        order = { readingOrder },
        titles = { window.value.chapters.associate { it.chapterId to it.title } },
        chapterId = { currentChapterId },
    )

    /** How far read aloud is through [chapterId] by its words, while it speaks that chapter of this series. */
    private fun spokenShare(chapterId: Long): Double? = readAloudSession.state.value
        .takeIf { it.isOf(novelId, sourceScoped) }
        ?.speech?.progress
        ?.takeIf { it.chapterId == chapterId && it.words > 0 }
        ?.let { it.wordsSpoken.toDouble() / it.words }

    /** The chapter read aloud is speaking, while it reads this series. */
    private fun spokenChapter(): Long? = readAloudSession.state.value
        .takeIf { it.isOf(novelId, sourceScoped) }
        ?.speech?.progress?.chapterId
        ?: readAloudSession.moves.value?.takeIf { it.series.includes(novelId, sourceScoped) }?.chapterId

    /**
     * The chapter this session opens on: the one being read when the process was killed, read aloud's
     * when the launch follows it, or the one the launch named. Seamless crossing means a session spans
     * many chapters with no open of their own, so the launch chapter can be several chapters behind by
     * then. `ReaderViewModel` restores the same thing from its own handle. Declared above every reader
     * of it, which would otherwise see 0 while this initializer had not run.
     */
    private val openingChapterId: Long = savedState.get<LongArray>(SAVED_PLACE)?.get(0)
        ?: spokenChapter()?.takeIf { followSession || readAloudSession.isSpoken(initialChapterId) }
        ?: initialChapterId

    /** Whether the first load reopens after a process kill, which [newestStampedChapter] may move on. */
    private var restoringAfterKill = savedState.contains(SAVED_PLACE)

    /** Loading until the first chapter renders, so the host shows a spinner rather than a blank page.
     *  Declared above the init block that calls load(), which would otherwise write it before it exists. */
    val loadState: StateFlow<ReaderLoadState>
        field = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Loading)

    /** The open chapter's bookmark state, seeded when it loads and flipped from the bar or the sheet.
     *  Above the init block for the same reason as [loadState]: the first load writes it. */
    val bookmarked: StateFlow<Boolean>
        field = MutableStateFlow(false)

    fun retryLoad() {
        textLoader.retryFailedPlugin()
        load()
    }

    /** The chapter the pending open fetches from its source rather than its downloaded copy. Any other
     *  open clears it, and Retry keeps it. */
    @Volatile
    private var sourceReloadId: Long? = null

    /** The chapter a step is opening to the chapter it left, which the skip setting marks once whichever load
     *  of that step commits: a text change mid-step starts the step's load over. Changed in the lane. */
    private var skipMark: Pair<Long, Long>? = null

    /**
     * The chapter on screen opened again where the reader is, as a chapter-text setting reloads it, with
     * its cached text dropped. [fromSource] skips a downloaded copy and the pictures cached of it. A load
     * already running is left alone.
     */
    fun reloadChapter(fromSource: Boolean) {
        viewModelScope.launchIO {
            val id = lane.withLock {
                if (loadState.value == ReaderLoadState.Loading) return@launchIO
                sourceReloadId = currentChapterId.takeIf { fromSource }
                currentChapterId.also { pendingChapterId = it }
            }
            htmlCache.remove(id)
            load()
        }
    }

    /**
     * The reader asking again for the chapter whose failure is drawn at that edge of the window, which
     * need not be a neighbour: past a chapter that fits on screen, the edge is further on. Immediate:
     * waiting out a cooldown the reader has just overridden by hand is the stranding the cooldown
     * exists to prevent, in miniature.
     */
    fun retryBoundary(forward: Boolean) {
        val edge = window.value.let { if (forward) it.failedNext else it.failedPrevious } ?: return
        // Dropped without republishing the window: the renderer is already showing its own progress
        // for this tap, and clearing the edge here would take that away and put it back.
        warmFailures.remove(edge.chapterId)
        // A plugin that failed to load gets another try, as the sheet's Retry gives one.
        textLoader.retryFailedPlugin()
        if (htmlCache.containsKey(edge.chapterId)) {
            viewModelScope.launchIO { rebuildWindow() }
        } else {
            warmNeighbour(edge.chapterId)
        }
    }

    /**
     * Serialises every change to which chapter is being read with every window published. Unordered,
     * a crossing could commit after the reader had crossed back, and a window built before an open
     * could be published under that open's generation, around the chapter the reader had just left.
     */
    private val lane = Mutex()

    /** Finishes each chapter once while this session or read aloud can, however often it completes. */
    private val finishHold = chapterFinish.hold()

    /** What a write this session reports needs, as it stands now: the write may land after the session. */
    private fun writeContext() = NovelWriteContext(memberIds, groupStitch, orderedIds, incognito)

    /** Where this series keeps its reader flags, its merge group's rather than [novelId]'s, which is whichever
     *  member owns the chapter opened. [novelId] alone once that read fails. */
    private val viewerFlags = CompletableDeferred<GroupViewerFlags>()

    /** Per-novel reader orientation override (a [ReaderOrientation] flagValue; 0 = follow the global
     *  default), in [viewerFlags]. */
    private val orientationOverride = MutableStateFlow(ReaderOrientation.DEFAULT.flagValue)

    fun setOrientation(flagValue: Int) {
        orientationOverride.value = flagValue
        flagWrites.launch {
            withContext(io) {
                viewerFlags.await().write { setNovelViewerFlags.awaitSetOrientation(it, flagValue.toLong()) }
            }
        }
    }

    /** The renderer this novel keeps for itself over the setting, null to follow it; in [viewerFlags]. */
    val renderingMode: StateFlow<NovelRenderingMode?>
        field = MutableStateFlow<NovelRenderingMode?>(null)

    /** The reading mode this novel keeps for itself over the setting, null to follow it; in [viewerFlags]. */
    val pageLayout: StateFlow<NovelPageLayout?>
        field = MutableStateFlow<NovelPageLayout?>(null)

    /** The novel's own layout, else the setting: what every "paged?" in this session asks. */
    val effectivePageLayout: StateFlow<NovelPageLayout> = combine(
        pageLayout,
        novelPreferences.readerPageLayout().changes(),
    ) { own, setting -> own ?: setting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, novelPreferences.readerPageLayout().get())

    /** The reading mode the viewport had when a sheet setting rebuilt it, which the next build reads once: the
     *  Activity does not survive the rebuild, so the provider cannot hold it. */
    var viewportRebuiltFrom: NovelPageLayout? = null

    /** Completes once [renderingMode] and [pageLayout] hold the novel's own choices, which the first viewport is
     *  built with. */
    val viewerFlagsLoaded: Deferred<Unit>
        field = CompletableDeferred<Unit>()

    fun setRenderingMode(mode: NovelRenderingMode?) {
        renderingMode.value = mode
        flagWrites.launch {
            withContext(io) { viewerFlags.await().write { setNovelViewerFlags.awaitSetRenderingMode(it, mode) } }
        }
    }

    fun setPageLayout(layout: NovelPageLayout?) {
        pageLayout.value = layout
        flagWrites.launch {
            withContext(io) { viewerFlags.await().write { setNovelViewerFlags.awaitSetPageLayout(it, layout) } }
        }
    }

    fun setKeepScreenOn(enabled: Boolean) = novelPreferences.readerKeepScreenOn().set(enabled)

    fun setBionicReading(enabled: Boolean) = novelPreferences.readerBionicReading().set(enabled)

    fun setFontSize(size: Int) = novelPreferences.readerFontSize().set(size)

    fun setFollowSystemTheme() = novelPreferences.readerFollowSystemTheme().set(true)

    /** Choosing a colour is choosing it over "Auto", so the follow-system flag clears with it. */
    fun setThemeColors(background: String, textColor: String) {
        novelPreferences.readerFollowSystemTheme().set(false)
        novelPreferences.readerBackgroundColor().set(background)
        novelPreferences.readerTextColor().set(textColor)
    }

    /** Reactive reader display settings; the screen resolves follow-system into colors. */
    val settings: StateFlow<NovelReaderSettings> = (
        NovelReaderSettings.watched(novelPreferences) + orientationOverride + pageLayout
        )
        .snapshotOnChange { NovelReaderSettings.read(novelPreferences, orientationOverride.value, pageLayout.value) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            NovelReaderSettings.read(novelPreferences, orientationOverride.value),
        )

    /** The chapter the viewport renders: null until the first chapter loads, and still null if that
     *  first load fails. A later failed load keeps the chapter already rendered. */
    data class LoadedChapter(
        val chapterId: Long,
        val title: String,
        /** The chapter path on its source, for the web actions. */
        val url: String,
        val html: String,
        /** The renderer [html] was processed for, which a viewport of the other one never draws. */
        val renderingMode: NovelRenderingMode,
        val baseUrl: String?,
        val progress: ChapterProgress.Percent,
        /** The line the reader had at the top, as characters into the chapter (`shownCharPrefix`), which a
         *  renderer lands on in place of [progress]; null where there is no line to hold. */
        val topLine: Int? = null,
        /** What the marker between two chapters reads (`NovelSeam`): the number its missing-chapters
         *  count is taken from, and whether this chapter's own copy is on disk. */
        val chapterNumber: Double,
        /** The library row this copy belongs to, which differs between a merged novel's sources. */
        val novelId: Long,
        /** That row's source, which its pictures are fetched with; null when the row is gone. */
        val sourceId: String?,
        /** The stylesheet that source ships for its chapters, which only the WebView rendering mode applies. */
        val sourceStylesheet: NovelChapterStylesheet? = null,
        val downloaded: Boolean,
        /** No chapter follows it to step forward to, the answer `chapterAfter` gives, so the end marker
         *  (`NovelSeam.end`) is drawn below it. */
        val isLast: Boolean,
        /** The numbers the session's chapters carry, which the seam's missing count leaves out. */
        val gapPresent: ChapterGap.Present = ChapterGap.Present.NONE,
    )

    /** The opened entry's own title, which a merged session keeps even as chapters cross sources. */
    internal val entryTitle: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    /** The opened novel's id once its row loads, which the app bar's title tap opens the details of. */
    internal val detailsNovelId: StateFlow<Long?>
        field = MutableStateFlow<Long?>(null)

    /** The opened novel's cover, which tints the chrome. Null until the row loads, and for no cover. */
    internal val cover: StateFlow<NovelCover?>
        field = MutableStateFlow<NovelCover?>(null)

    /**
     * The chapter being read, which the renderer decides once it holds more than one: crossing a
     * boundary changes this without any load. Everything a user sees named or acted on (the title,
     * the bookmark, the web actions, the chapter list's mark) follows it rather than the load.
     */
    val chapter: StateFlow<LoadedChapter?>
        field = MutableStateFlow<LoadedChapter?>(null)

    /**
     * The chapters to render, in reading order: the one being read, the chapter before it, and the
     * forward reach of [NovelWindowReach] after it, each once it is warmed. The host starts a renderer
     * over on a new [Window.generation] through [landingOf] and reports back through [rendererLanded].
     */
    val window: StateFlow<Window>
        field = MutableStateFlow(Window())

    /**
     * [generation] rises on every explicit open, which is what tells a renderer to start over rather
     * than grow: an open lands on [anchorId], where a crossing keeps the reader exactly where the
     * scroll put it. Without it the two are indistinguishable, since an open onto an already-warmed
     * neighbour produces the same set of chapters as scrolling into it.
     */
    data class Window(
        val generation: Int = 0,
        val anchorId: Long = -1L,
        val chapters: List<LoadedChapter> = emptyList(),
        /** Set where the reader runs out of text because the chapter beyond it would not load, so
         *  the renderer can say so at the edge instead of simply ending. */
        val failedPrevious: BoundaryFailure? = null,
        val failedNext: BoundaryFailure? = null,
        /** The chapter before is on its way into the window: warming, or waiting on the reach below. */
        val previousJoining: Boolean = false,
    )

    /**
     * Why [chapterId] is missing from the window, for the message the renderer shows and the chapter
     * its retry asks for. [failedAtElapsedMs] is carried so a second failure of the same chapter is a
     * different value: without it a retry that fails the same way is indistinguishable from the first,
     * and the renderer would be left showing whatever it drew while the retry was running.
     */
    data class BoundaryFailure(val message: String?, val failedAtElapsedMs: Long, val chapterId: Long)

    @Volatile
    private var openGeneration = 0

    /**
     * How far down the open chapter the reader is, to the hundredth the store keeps. Reported on every
     * scroll frame, which is what the navigator follows.
     */
    val progress: StateFlow<ChapterProgress.Percent>
        field = MutableStateFlow(ChapterProgress.Percent(0))

    /** The line at the top of the screen, with the chapter it is in, as the renderer last reported it. */
    @Volatile
    private var latestTopLine: Pair<Long, Int?>? = null

    /** Reported on every move, null while the chapter's text starts on screen. Only [landingOf] reads it. */
    fun reportTopLine(id: Long, line: Int?) {
        if (reportsCount()) latestTopLine = id to line
    }

    /**
     * The generation the renderer has started on, which the host reports through [rendererLanded].
     * Until it is [openGeneration], what the renderer reports is about the window an open replaced,
     * and taking its word moved the reader back to the chapter they had just left.
     */
    @Volatile
    private var rendererGeneration = -1

    /** Which chapter may be read while the renderer settles on the one it started on. */
    @Volatile
    private var landing = NovelOpenLanding(openingChapterId) { false }

    /** The chapter the renderer named last, with the generation it was named in, which [cross] moves
     *  to. A report racing an open carries the old generation, so it cannot cross out of the open. */
    @Volatile
    private var visibleReport: Pair<Int, Long>? = null

    /** The position reported last, so a crossing shows the arriving chapter's own rather than the
     *  departed one's until the next report. */
    @Volatile
    private var latestReport: Pair<Long, ChapterProgress.Percent>? = null

    /**
     * The anchor of [window], at where the reader is now. An Activity rebuilt mid-chapter renders the
     * same window again, and the position the chapter had when it was opened put the reader back there
     * and let the next save overwrite theirs. The line at the top is carried with the percent, since a
     * percent moves the top line once the width changes, and within a chapter's last screen every
     * position reads 100.
     */
    fun landingOf(window: Window): LoadedChapter {
        val anchor = window.chapters.first { it.chapterId == window.anchorId }
        if (anchor.chapterId != currentChapterId) return anchor
        val line = latestTopLine?.takeIf { it.first == anchor.chapterId }?.second
        return anchor.copy(progress = progress.value, topLine = line)
    }

    /** The renderer has started over on [generation]'s anchor, so what it reports is the reader's again. */
    fun rendererLanded(generation: Int) {
        val opened = currentChapterId
        landing = NovelOpenLanding(opened) { id ->
            val index = orderedIds.indexOf(id)
            index >= 0 && index < orderedIds.indexOf(opened)
        }
        rendererGeneration = generation
    }

    private fun reportsCount() = rendererGeneration == openGeneration

    /**
     * Every scroll persists, debounced. Keying the write on a settle instead lost an auto-scrolled or
     * scrubbed read entirely, because those move the viewport without a touch to end.
     */
    fun reportProgress(id: Long, progress: ChapterProgress.Percent) = takeReport(id, progress, settled = false)

    /** A renderer that knows its own scroll has settled, which the web layer reports on scrollend. */
    fun saveProgress(id: Long, progress: ChapterProgress.Percent) = takeReport(id, progress, settled = true)

    private fun takeReport(id: Long, reported: ChapterProgress.Percent, settled: Boolean) {
        if (!reportsCount()) return
        // A report for a chapter the window no longer holds is a straggler from a crossing, and
        // writing it would move a position the reader has already left behind.
        if (window.value.chapters.none { it.chapterId == id }) return
        val clamped = ChapterProgress.Percent.clamped(reported.hundredths)
        val wasLanding = !landing.settled
        // In whole percents, so a landing still settling by a fraction of one is not the reader moving.
        if (!landing.counts(id, clamped.wholePercent.toInt())) return
        // The reader moved, so the chapter the renderer named while the landing held it now stands.
        if (wasLanding && landing.settled) requestCrossing()
        latestReport = id to clamped
        if (id == currentChapterId) {
            val before = progress.value.wholePercent
            progress.value = clamped
            // Reaching the threshold is what lets the next chapter join below, so the window follows at once.
            val threshold = novelPreferences.readerAutoLoadNextAt().get()
            if (before < threshold && clamped.wholePercent >= threshold) viewModelScope.launchIO { rebuildWindow() }
        }
        // The reader moving settles the landing, which is what lets it overwrite read aloud's place.
        progressSlot.report(id, clamped, settled, landing.settled, writeContext())
    }

    /** Write the last reported position now. The host calls this on pause, so being backgrounded or
     *  killed cannot drop a held write. */
    fun flushProgress() = progressSlot.flush()

    /** The chapter being read with its position, kept for a process kill as manga keeps its page: in incognito the
     *  database holds none to reopen at. Kept as each is written, so it is never a place the save skipped, and only
     *  once the chapter has rendered, or a kill during a slow first load reopened History's newest instead. One
     *  value, posted to Main in call order and checked there: Android copies saved state on Main as it saves,
     *  so a write from IO could break that copy, and a crossing's departed chapter is never saved. */
    private fun keepPlace(chapterId: Long, at: ChapterProgress.Percent) {
        viewModelScope.launchUI {
            if (chapterId != currentChapterId) return@launchUI
            longArrayOf(chapterId, at.hundredths).let {
                savedState[SAVED_PLACE] = it
                keptPlace = it
            }
        }
    }

    // The kept place, read on IO without touching the saved state map Main writes and copies.
    @Volatile
    private var keptPlace: LongArray? = savedState.get<LongArray>(SAVED_PLACE)

    /** The newest position this session has for [chapterId] that the database may not hold yet: one held
     *  back, one waiting to be written, or, in incognito, the one kept across a process kill. Outside it the
     *  database wins, since the kept state is a snapshot from when the reader stopped and read aloud
     *  can write on behind it. */
    private suspend fun unstoredPosition(chapterId: Long, ownerId: Long): Long? =
        progressSlot.unstored(chapterId)?.hundredths
            ?: keptPlace
                ?.takeIf { it[0] == chapterId && incognito.of(ownerId) }
                ?.get(1)

    /** The chapter on screen, which a merged session moves across sources. Every bar verb acts on this
     *  one, so it advances only once a chapter has actually rendered. */
    @Volatile
    private var currentChapterId: Long = openingChapterId

    /** The chapter a load is aiming at, which is what a retry repeats. Separate from [currentChapterId]
     *  because a load that fails leaves the reader showing what it had. */
    @Volatile
    private var pendingChapterId: Long = openingChapterId

    /** Every chapter this session can reach, in reading order, duplicates and hidden ones already gone. */
    @Volatile
    private var orderedIds: List<Long> = emptyList()

    /** Which of [orderedIds] a forward step may land on, per the skip settings. A back step ignores it,
     *  so the chapter just finished stays reachable from the one after it. */
    @Volatile
    private var forwardEligibleIds: Set<Long> = emptySet()

    /** Every number the session's chapters carry, hidden ones included, which a seam's missing count leaves out. */
    @Volatile
    private var gapPresent: ChapterGap.Present = ChapterGap.Present.NONE

    /** The novels of the opened one's merge group, itself alone when ungrouped, and the group's stored
     *  stitch (empty when ungrouped). Resolved with [orderedIds], in either scope. */
    @Volatile
    var memberIds: List<Long> = listOf(novelId)
        private set

    @Volatile
    private var groupStitch: List<ChapterUnit> = emptyList()

    /** Which copy of a merged chapter an open reads: one on disk over the one online. Resolved with
     *  [orderedIds], which lists those copies; null when ungrouped or source-scoped. */
    @Volatile
    private var copiesToOpen: CopyToOpen<NovelChapter>? = null

    /** The copy a download of each chapter fetches, resolved with [orderedIds]: an installed source's in
     *  place of one whose plugin is gone. Each chapter's own when ungrouped, source-scoped or unstitched. */
    @Volatile
    private var downloadTargets: DownloadTargets = DownloadTargets.OWN

    /**
     * Reads [orderedIds] and the rest of the session's order again, from the chapter being read. Declared
     * above init, which starts the load on IO: one declared below can still be unassigned when it is read.
     */
    private val orderResolver = NovelReadingOrderResolver(
        getNextNovelChapter,
        novelRepo,
        chapterRepo,
        copyMarks,
        novelPreferences,
        basePreferences,
        novelDownloadCache,
        sourceManager,
    )

    /** The order [resolveReadingOrder] read last, which [chapterAfter] steps through. */
    @Volatile
    private var readingOrder: NovelReadingOrder? = null

    private val mergeScope = MergeScope.of(sourceScoped)

    /** What the navigator's chapter buttons enable on. */
    val chapterNeighbours: StateFlow<Neighbours>
        field = MutableStateFlow(Neighbours())

    data class Neighbours(val previous: Long? = null, val next: Long? = null)

    /** Chapters warmed by the forward prefetch, newest first, so a forward step renders without a
     *  round trip. Bounded, because a long session would otherwise hold every chapter it has read. */
    private val htmlCache: MutableMap<Long, NovelChapterText> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, NovelChapterText>(MAX_CACHED_CHAPTERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, NovelChapterText>) =
                size > MAX_CACHED_CHAPTERS
        },
    )

    /**
     * Raised whenever [htmlCache] is cleared for a chapter-text setting or the renderer, so text a load or a
     * warm was already processing for the old ones is dropped rather than cached or shown. The pipeline's
     * output depends on the renderer: the native one keeps event handlers its text view never runs, and the
     * WebView would run them.
     */
    @Volatile
    private var textGeneration = 0

    /** The last warm failure per chapter, which [ChapterRetryCooldown] reads to decide whether the window
     *  may reach for that chapter again unprompted. An explicit open clears the lot. */
    private val warmFailures = ChapterRetryCooldown.Failures()

    /** Chapters with a warm already running, so two crossings in quick succession do not fetch the
     *  same chapter twice. */
    private val warmsInFlight = InFlightLoads<Long>()

    /** Chapters the renderer found fit on one screen, so a forward step from one reads it. Kept current
     *  rather than latched, since a chapter can measure short before its images land. */
    private val fitsOnScreen: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    fun reportFitsOnScreen(chapterId: Long, fits: Boolean) {
        // A measurement of the page an open replaced says nothing about the window that replaced it.
        if (!reportsCount()) return
        val changed = if (fits) fitsOnScreen.add(chapterId) else fitsOnScreen.remove(chapterId)
        // A chapter that turns out to fit is one the reader cannot scroll past, so the window has to
        // reach beyond it; one that grows past a screen lets the window shrink back.
        if (!changed) return
        viewModelScope.launchIO {
            lane.withLock {
                extendWindowForward()
                publishWindow()
            }
        }
    }

    /** A chapter's last line reached the screen, once its images had landed. */
    fun reportChapterEndSeen(chapterId: Long) {
        // From the page an open replaced, or of a chapter the landing holds, it is not the reader's.
        if (!reportsCount() || !landing.mayRead(chapterId)) return
        val hasNext = !(isLastInOrder(chapterId) ?: return)
        val chapter = window.value.chapters.firstOrNull { it.chapterId == chapterId } ?: return
        // A position it reported on opening, still waiting to be written, would land after the mark
        // and put a chapter just read back at 0.
        if (!hasNext) progressSlot.discardIf { it == chapterId }
        val context = writeContext()
        // Off the caller's thread, which for the native renderer is the main one, since this parses.
        progressSlot.launch {
            withContext(io) {
                if (NovelLeaveRule.readsOnReachingEnd(hasNext, chapter.html)) {
                    progressSlot.finishUnlessRead(chapterId, context)
                }
            }
        }
    }

    init {
        readAloudSession.register(readAloud)
        // One series, one place: a launch naming another chapter of the series read aloud is reading,
        // rather than following it, is the reader choosing to read there.
        // Asked of any scope: a chapter opened from one source of the series is no less the reader's own pick,
        // and the series in another view of it (a merged one by source) is one whatever the chapter.
        // A restore reopens a snapshot read aloud may have spoken past, and [followReadAloud] takes the reader on
        // to read aloud's chapter, so that is the one asked about.
        val opening = if (restoringAfterKill) spokenChapter() ?: openingChapterId else openingChapterId
        readAloudSession.state.value.let { session ->
            val spoken = readAloudSession.moves.value?.chapterId
            val series = session.series
            val conflicts = series != null && series.touches(novelId) &&
                (
                    !series.includes(novelId, sourceScoped) ||
                        (spoken != null && !readAloudSession.isSpoken(opening))
                    )
            if (!followSession && conflicts) readAloudSession.stop()
        }
        holdReadClockWhileSpeaking()
        followReadAloud()
        // Kept as each is written, so it is never a place the save skipped.
        progressSlot.persisted.onEach { (id, at) -> keepPlace(id, at) }.launchIn(viewModelScope)
        viewModelScope.launchIO {
            val novel = try {
                val ownerId = getNextNovelChapter.viewerFlags(novelId).also(viewerFlags::complete).ownerId
                val opened = novelRepo.getById(novelId)
                val owner = if (ownerId == novelId) opened else novelRepo.getById(ownerId)
                owner?.let {
                    NovelRenderingMode.ofFlags(it.viewerFlags)?.let { mode ->
                        renderingMode.value = mode
                    }
                    pageLayout.value = NovelPageLayout.ofFlags(it.viewerFlags)
                    orientationOverride.value = it.readerOrientation.toInt()
                }
                opened
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // A failed read leaves the default, so the reader still opens.
                logcat(LogPriority.ERROR, e) { "Failed to read novel $novelId's own reader settings" }
                null
            } finally {
                viewerFlags.complete(GroupViewerFlags.of(novelId, emptyList()))
            }
            // Once the settings carry the novel's own choices too, or the first tap zones are the setting's.
            settings.first {
                it ==
                    NovelReaderSettings.read(novelPreferences, orientationOverride.value, pageLayout.value)
            }
            viewerFlagsLoaded.complete(Unit)
            novel?.let {
                entryTitle.value = it.title
                detailsNovelId.value = it.id
                cover.value = it.thumbnailUrl?.takeIf(String::isNotBlank)?.let(it::asNovelCover)
            }
        }
        // A page saved in the in-app browser, whenever it lands, even after the browser closed: the open
        // chapter reloads, and a chapter that failed to open is opened, the case the page is there for.
        pageFetcher.chapterSaved
            .onEach { id ->
                when (id) {
                    currentChapterId -> reloadChapter(fromSource = false)
                    (loadState.value as? ReaderLoadState.Failed)?.chapterId -> retryLoad()
                }
            }
            .launchIn(viewModelScope)
        // The cache holds pipeline output, so a chapter-text setting reaches the open chapter and the
        // prefetched next one only by dropping both and running it again.
        // The novel's own mode reprocesses the text as a changed setting does, once it has been read: the
        // read itself is no change, since the first load waits for it.
        val modeChanges = flow {
            viewerFlagsLoaded.await()
            emitAll(renderingMode.drop(1))
        }
        merge(textLoader.settingsChanged, modeChanges.map { })
            .onEach {
                synchronized(htmlCache) {
                    textGeneration++
                    htmlCache.clear()
                }
                // Re-aimed at what is on screen: a seamless crossing moves the reader into a chapter
                // without opening it, so the load would otherwise reopen the one the session started
                // on. Not done in cross(), which would also redirect a retry. A step still loading keeps
                // its target, or the reload silently dropped it. In the lane, as goTo sets it there.
                lane.withLock {
                    if (loadState.value != ReaderLoadState.Loading) {
                        pendingChapterId = currentChapterId
                        sourceReloadId = null
                    }
                }
                load()
            }
            .launchIn(viewModelScope)
        // Reports reach the next chapter by crossing the threshold, which a threshold lowered below the
        // reader's place never does. The window re-reads it, and never takes back a chapter it holds.
        novelPreferences.readerAutoLoadNextAt().changes()
            .drop(1)
            .onEach { rebuildWindow() }
            .flowOn(io)
            .launchIn(viewModelScope)
        load()
    }

    /**
     * Jump to [chapterId] from the chapter list. A no-op on the chapter already open. The engine retries
     * a failed pick as a pick, so a plugin that failed to load gets another try here, as [retryLoad] gives.
     */
    fun open(chapterId: Long) {
        if (chapterId == currentChapterId && chapter.value != null) return
        readAloud.onUserNavigated()
        textLoader.retryFailedPlugin()
        goTo(chapterId)
    }

    /** Forward only, so it is the step that can mark the departed chapter read. */
    fun nextChapter(): Boolean = chapterNeighbours.value.next?.let {
        readAloud.onUserNavigated()
        goTo(it, markDepartedRead = true)
        true
    } ?: false

    fun previousChapter(): Boolean = chapterNeighbours.value.previous?.let {
        readAloud.onUserNavigated()
        goTo(it)
        true
    } ?: false

    /**
     * The renderer scrolled into a different chapter of the window. Not a step: nothing is fetched
     * and nothing is skipped, so `markReadOnSkip` stays out of it. Scrolling forward past a chapter
     * reads it through [NovelLeaveRule], which is the only way one shorter than the screen ever is.
     */
    fun reportVisibleChapter(chapterId: Long) {
        // Read once: an open committing between the check and the stamp would pass a report about
        // the old window off as one about the new.
        val generation = openGeneration
        if (rendererGeneration != generation) return
        if (window.value.chapters.none { it.chapterId == chapterId }) return
        visibleReport = generation to chapterId
        if (landing.mayRead(chapterId)) requestCrossing()
    }

    /** The reader dragged the page or pressed a key that scrolls it, which the host sees first. The
     *  landing settles on the next report that moved, not here: a drag can scroll nothing. */
    fun readerMoved() = landing.readerMoved()

    /**
     * A progress write, since the chapters a forward crossing passes are finished only here, and leaving
     * the reader mid-fling is when the last crossing is still waiting for the lane.
     */
    private fun requestCrossing() {
        progressSlot.launch {
            withContext(io) {
                val passed = lane.withLock { cross() }
                val context = writeContext()
                passed.forEach { progressSlot.write(it, ChapterProgress.Percent.END, context) }
            }
        }
    }

    /**
     * Moves the session onto the chapter the renderer named last, and returns the chapters a forward
     * move passed, for the caller to finish off the lane. It reads the latest report rather than the
     * one that asked, so a quick back and forth across a seam settles where the reader stopped; a
     * report that came back to the chapter being read settles nothing.
     */
    private suspend fun cross(): List<Long> {
        val (generation, target) = visibleReport ?: return emptyList()
        if (generation != openGeneration || target == currentChapterId || !landing.mayRead(target)) return emptyList()
        val arriving = window.value.chapters.firstOrNull { it.chapterId == target } ?: return emptyList()
        val passed = NovelLeaveRule.passedGoingForward(
            window = window.value.chapters.map { it.chapterId },
            from = currentChapterId,
            to = target,
        )
        // Read before anything moves, so a failure leaves the session on the chapter it was reading.
        val owner = chapterRepo.getById(target)?.novelId ?: currentNovelId
        val bookmarked = isBookmarkedInGroup(target)
        val around = neighboursOf(target, departed = currentChapterId)
        // The window re-centres on where the reader now is, which warms the chapter beyond and
        // lets the one two behind go.
        val recentred = buildWindow(arriving, around, openGeneration)
        // A partial position still pending for a chapter being read in full would race the mark.
        progressSlot.discardIf { it in passed }
        flushProgress()
        updateHistory()
        currentChapterId = target
        currentNovelId = owner
        startReadSession()
        this.bookmarked.value = bookmarked
        chapter.value = arriving
        // Its own first report usually beat this here, and was not the current chapter's when it came.
        progress.value = latestReport?.takeIf { it.first == target }?.second ?: arriving.progress
        keepPlace(target, progress.value)
        settleNeighbours(around)
        window.value = recentred
        return passed
    }

    private fun goTo(chapterId: Long, markDepartedRead: Boolean = false) {
        viewModelScope.launchIO {
            // The departed chapter is stamped into history before the switch, and marked read once the
            // chapter it steps to has landed, since a failed load leaves the reader in it. Its pending
            // position is written first, or the debounce would still be waiting when the chapter it
            // belongs to stops being current. With the lane held, so a crossing cannot stamp the same
            // stretch of reading again or move the chapter the mark is for.
            lane.withLock {
                flushProgress()
                updateHistory()
                pendingChapterId = chapterId
                sourceReloadId = null
                val departed = when {
                    !markDepartedRead -> null
                    // One that fit on the screen was read in full, whatever the skip setting says: it has
                    // no scroll room, so this step is the only point it can be called finished.
                    currentChapterId in fitsOnScreen -> {
                        progressSlot.persist(currentChapterId, ChapterProgress.Percent.END, writeContext())
                        null
                    }
                    else -> currentChapterId
                }
                skipMark = departed?.let { chapterId to it }
            }
            load()
        }
    }

    /**
     * A missing row, an uninstalled source or a parse failure leaves the rendered chapter as it was
     * and reports [ReaderLoadState.Failed], so the host offers a retry rather than tearing down or
     * leaving the reader looking like nothing happened.
     */
    private fun load() {
        var requested = pendingChapterId
        var target = requested
        // An explicit open is the reader asking for chapters afresh, so nothing a previous warm
        // recorded may go on suppressing one. Without this the window strands on a chapter that has
        // since recovered, which is the bug tsundoku's own latch shipped with.
        warmFailures.clear()
        loadState.value = ReaderLoadState.Loading
        viewModelScope.launchIO {
            try {
                // The text is processed for the renderer the novel keeps, read at open, so the first chapter
                // is processed once, for the renderer that will draw it.
                viewerFlagsLoaded.await()
                val generation = textGeneration
                if (orderedIds.isEmpty()) resolveReadingOrder()
                if (restoringAfterKill) {
                    restoringAfterKill = false
                    newestStampedChapter()?.let { newest ->
                        lane.withLock {
                            if (requested != pendingChapterId) return@withLock
                            pendingChapterId = newest
                            requested = newest
                            currentChapterId = newest
                        }
                        // The order keeps the chapter it was resolved around, which is no longer the one opening
                        resolveReadingOrder()
                    }
                }
                target = copiesToOpen?.idOf(requested) ?: requested
                val row = chapterRepo.getById(target) ?: error("Chapter not found: $target")
                // A warm already fetching it is waited on, rather than fetched again beside it, which is
                // how read aloud reaching a chapter's end mid-warm opens the next chapter.
                warmsInFlight.awaitIdle(row.id)
                val fromSource = sourceReloadId == row.id
                val text = htmlCache[row.id] ?: loadChapterHtml(row, fromSource).also { loaded ->
                    cacheIfCurrent(row.id, loaded, generation)
                    if (fromSource) {
                        sourceReloadId = null
                        // The source's own addresses, before the renderer asks for them: a download's copy
                        // has its pictures inlined, so the one on screen names none of what is cached.
                        pictureCache.evict(loaded.html, loaded.baseUrl, novelRepo.getById(row.novelId)?.source)
                    }
                }
                val bookmarked = isBookmarkedInGroup(row.id)
                lane.withLock {
                    // A later open overtook this one while it loaded, and committing it now would put
                    // the reader back on the chapter they had moved on from. A text change since has
                    // its own load running, for the text this one processed by the old settings.
                    if (requested != pendingChapterId || generation != textGeneration) return@launchIO
                    commitOpen(row, text, bookmarked)
                    skipMark?.takeIf { (target, _) -> target == requested }?.let { (_, departed) ->
                        skipMark = null
                        markReadOnSkip(departed)
                    }
                }
                loadState.value = ReaderLoadState.Idle
            } catch (e: Throwable) {
                // Leaving the reader cancels this scope, and swallowing that would report a load
                // failure for a chapter nobody is waiting for any more.
                if (e is CancellationException) throw e
                if (requested != pendingChapterId) return@launchIO
                logcat(LogPriority.ERROR, e) { "Failed to load novel chapter $target" }
                // A failed step stamped the chapter it left into history, and the reader goes on in it.
                if (chapter.value != null) startReadSession(resume = true)
                loadState.value = ReaderLoadState.Failed(
                    with(context) { e.loadFailureMessage },
                    canKeepReading = chapter.value != null,
                    chapterId = target,
                    blockedUrl = e.cloudflareBlockedUrl(),
                )
            }
        }
    }

    /**
     * Makes [row] the chapter being read, with the lane held. Everything that reads or can throw runs
     * before any state moves, since the reader goes on rendering the chapter it already had: moving
     * first pointed the bookmark and the web actions at a chapter that never appeared, or raised a
     * generation no window was published under, which closed the renderer's reports for good.
     */
    private suspend fun commitOpen(row: NovelChapter, text: NovelChapterText, bookmarked: Boolean) {
        val opened = row.toLoadedChapter(text)
        val around = neighboursOf(row.id)
        // A new generation tells the renderer to start over, and it closes reports until it has.
        val window = buildWindow(opened, around, openGeneration + 1)
        // The window being replaced takes its reader's last position with it.
        flushProgress()
        // A reload of the chapter already open, after a chapter-text setting changed, keeps its timer
        // and its place: restarting either lost what the reader had done since it opened.
        val reloading = row.id == currentChapterId && chapter.value != null
        startReadSession(resume = reloading)
        currentChapterId = row.id
        currentNovelId = row.novelId
        this.bookmarked.value = bookmarked
        chapter.value = opened
        if (!reloading) {
            progress.value = opened.progress
            keepPlace(row.id, opened.progress)
            latestTopLine = null
        }
        openGeneration = window.generation
        visibleReport = openGeneration to opened.chapterId
        settleNeighbours(around)
        this.window.value = window
    }

    private suspend fun NovelChapter.toLoadedChapter(text: NovelChapterText): LoadedChapter {
        val novel = novelRepo.getById(novelId)
        return LoadedChapter(
            chapterId = id,
            title = name,
            url = url,
            html = text.html,
            renderingMode = text.renderingMode,
            baseUrl = text.baseUrl,
            // Where read aloud is speaking first: it is ahead of every write, and in incognito or a chapter
            // already read none of them holds it.
            progress = spokenShare(id)?.let(ChapterProgress.Percent::of) ?: NovelResume.progress(
                read,
                unstoredPosition(id, novelId) ?: lastTextProgress,
                novelPreferences.readerPreserveReadingPosition().get(),
            ),
            chapterNumber = chapterNumber,
            novelId = novelId,
            sourceId = novel?.source,
            sourceStylesheet = novel?.source?.let { sourceManager.get(it) }?.chapterStylesheet,
            // This copy's own, as manga's transition reads the chapter it will load rather than the group's;
            // a local novel's chapters count, as a local manga's do there.
            downloaded = novel?.let { novelDownloadCache.isOnDevice(it, this) } == true,
            isLast = isLastInOrder(id) == true,
            gapPresent = gapPresent,
        )
    }

    /** [updateHistory] on the app scope, so a reader closing still writes it, as manga's saveHistory. Read
     *  aloud going on keeps its own clock, so this one stays stopped until the reader comes back. */
    fun saveHistory() {
        onScreen = false
        readAloudSession.setOnScreen(readAloud, false)
        // Immediate so the read time is taken when the reader pauses
        appScope.launch(Dispatchers.Main.immediate) { updateHistory() }
    }

    /** Stamp the current chapter into novel history and accumulate this session's read time. Called on
     *  chapter switch and, through [ReaderProvider.saveHistory], on leaving the reader. [onlyRead] writes
     *  nothing when no time was read, for a move this reader made only to follow read aloud. */
    private suspend fun updateHistory(onlyRead: Boolean = false) {
        val chapter = this.chapter.value ?: return
        if (incognito.of(chapter.novelId)) return
        val id = chapter.chapterId
        val now = Clock.System.now()
        val duration = chapterReadSession.take(now)
        if (onlyRead && duration <= 0L) return
        upsertNovelHistory.await(NovelHistoryUpdate(id, now, duration))
        readingLog.record(ContentType.NOVELS, id, now, duration)
    }

    /**
     * The chapter History stamped last across the group, when the reading order holds it. The host's
     * snapshot names the chapter on screen when the reader stopped, and read aloud can have moved on
     * since. Incognito stamps nothing, so there the snapshot stands.
     */
    private suspend fun newestStampedChapter(): Long? {
        val owner = chapterRepo.getById(currentChapterId)?.novelId ?: novelId
        if (incognito.of(owner)) return null
        return memberIds.flatMap { novelHistory.getHistoryByNovelId(it) }
            .mapNotNull { row -> row.readAt?.let { it to row.chapterId } }
            // A step stamps the chapter it leaves and the one it reaches in the same instant, and read aloud
            // only steps forward, so the later one in reading order wins a tie.
            .maxWithOrNull(compareBy<Pair<Instant, Long>> { it.first }.thenBy { orderedIds.indexOf(it.second) })
            ?.second
            ?.takeIf { it in orderedIds && it != currentChapterId }
    }

    private var isMenuOpen = false

    /** Called through [ReaderProvider.setMenuOpen]: time in the menu is not reading. */
    fun setMenuOpen(open: Boolean) {
        isMenuOpen = open
        holdReadClock()
    }

    /** Whether read aloud is playing this series, whose time its own clock counts. */
    private fun isSpeaking(state: ReadAloudSessionState = readAloudSession.state.value): Boolean =
        state.isOf(novelId, sourceScoped) && state.speech.playback == TtsPlayback.Playing

    /** One clock at a time: while speech plays, the time is listening, which read aloud counts. */
    private fun holdReadClockWhileSpeaking() {
        readAloudSession.state
            .map(::isSpeaking)
            .distinctUntilChanged()
            .onEach { holdReadClock() }
            .launchIn(viewModelScope)
    }

    private fun holdReadClock() = chapterReadSession.hold(held = isMenuOpen || isSpeaking(), now = Clock.System.now())

    /** Called through [ReaderProvider.restartReadTimer] on resume, since leaving the reader stamped
     *  [updateHistory] and stopped the clock. */
    fun restartReadTimer() {
        onScreen = true
        readAloudSession.setOnScreen(readAloud, true)
        chapterReadSession.resume(Clock.System.now())
    }

    /**
     * Starts the read clock, or [resume]s it, only while the reader is on screen. A chapter landing while
     * the reader is away (read aloud moving on, a page saved in the in-app browser, a slow open) otherwise
     * starts it, and [restartReadTimer]'s resume then keeps that start.
     */
    private fun startReadSession(resume: Boolean = false) {
        if (!onScreen) return
        val now = Clock.System.now()
        if (resume) chapterReadSession.resume(now) else chapterReadSession.start(now)
    }

    /** Opens each chapter read aloud moves on to by itself, whether or not this reader is on screen. */
    private fun followReadAloud() {
        readAloudSession.moves
            .onEach { move ->
                if (move == null || !move.series.includes(novelId, sourceScoped)) return@onEach
                // On screen, a chapter the window holds is reached by the scroll, which a new open would undo.
                val inWindow = onScreen && window.value.chapters.any { it.chapterId == move.chapterId }
                if (!inWindow && move.chapterId != currentChapterId && move.chapterId != pendingChapterId) {
                    followSession(move.chapterId)
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Opens [chapterId], where read aloud has moved on to. Not a step of the reader's: the chapter left was
     * finished by speech and History stamped by it, so nothing is stamped, marked or flushed here, only
     * the time read by hand before speech took over. The order is read again around the new chapter.
     */
    private fun followSession(chapterId: Long) {
        viewModelScope.launchIO {
            lane.withLock {
                updateHistory(onlyRead = true)
                pendingChapterId = chapterId
                sourceReloadId = null
                skipMark = null
                resolveReadingOrder(around = chapterId)
            }
            load()
        }
    }

    /** Called through [ReaderProvider.onActivityFinish] on leaving the reader, which is when the
     *  chapters finishing queued for deletion go, as in manga's `ReaderViewModel.onActivityFinish`. */
    fun onActivityFinish() {
        appScope.launch {
            // A finish written before leaving may still queue the chapter it read, as on manga
            progressSlot.awaitLanded()
            deleteChaptersBehindReader.deletePending()
        }
    }

    /**
     * The chapter sheet's rows, in reading order, each showing the merge group's read, bookmarked and
     * on-disk state as the details list does. Cold, so the list is only built while the sheet is open,
     * and re-emitted as downloads move or the reader changes chapter. Read fresh rather than from the
     * session's opening, so a chapter this session marked shows as marked.
     */
    fun chapterRows(words: ChapterTitleWords): Flow<List<ReaderChapterRow>> = flow {
        if (orderedIds.isEmpty()) resolveReadingOrder()
        val pooled = groupPool()
        val chapters = pooled.resolve(orderedIds)
        val sourceNames = chapterSourceNames()
        val novels = novelRepo.ownersOf(pooled + chapters)
        val settings = getNextNovelChapter.chapterSettings(novelId)
        val numberOnly = settings?.effectiveHideChapterTitles(novelPreferences) == true
        emitAll(
            combine(downloadManager.queueState, chapter) { queue, _ ->
                val flags = groupFlags(pooled, chapters, novels)
                val queued = queue.associateBy { it.chapterId }
                chapters.map {
                    it.toReaderChapterRow(sourceNames, queued, flags, numberOnly, words, downloadTargets).let { row ->
                        val volume = novelChapterVolume(novels[it.novelId]?.url, it.url, it.name)
                        row.copy(
                            volume = volume?.shown(
                                localVolumes.looseFiles().value,
                                localVolumes.volumes(ContentType.NOVELS).value,
                            ),
                        )
                    }
                }
            },
        )
    }.flowOn(io)

    /** Per-source display names keyed by the merge group's members, for a merged novel's source labels. */
    private suspend fun chapterSourceNames(): Map<Long, String> = mergeSourceLabels(
        memberIds.associateWith { id ->
            val novel = novelRepo.getById(id)
            val sourceName = textLoader.cachedSource(id)?.name ?: novel?.source?.let { sourceManager.nameOf(it) } ?: ""
            mergeMemberName(sourceName, novel?.title.orEmpty(), LocalNovelSource.isLocal(novel?.source))
        },
    )

    /** Reaches every source's copy of the chapter, as the manga sheet and the details list do. */
    fun setChapterRead(chapterIds: List<Long>, read: Boolean) {
        appScope.launch {
            val copies = groupCopies(chapterIds.toSet())
            setNovelReadStatus.await(read, copies, keepDownloaded = expandToUnits(heldIds(), groupStitch))
        }
    }

    /** The chapter being read and every chapter the window holds beside it, see ReaderChapterList.heldChapterIds. */
    val heldChapterIds: Flow<Set<Long>>
        get() = combine(window, chapter) { _, _ -> heldIds() }.distinctUntilChanged()

    private fun heldIds(): Set<Long> =
        window.value.chapters.mapTo(hashSetOf(currentChapterId)) { it.chapterId }

    /** Where under Pictures a chapter's picture saves, by the setting a manga page saves by. */
    fun pictureFolder(): String = savedPictureFolder(entryTitle.value, readerPreferences.folderPerManga.get())

    /**
     * Makes a chapter's picture, [picture]'s bytes, the novel's cover, with a manga page's answers: a novel
     * that would lose the cover with its library entry is asked to be added first. On the app scope, so
     * closing the viewer does not drop the write.
     */
    suspend fun setPictureAsCover(picture: suspend () -> ByteArray?): ReaderViewModel.SetAsCoverResult =
        appScope.async {
            try {
                val novel = novelRepo.getById(novelId) ?: return@async ReaderViewModel.SetAsCoverResult.Error
                if (!SetNovelCustomCover.canSet(novel)) return@async ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
                val bytes = picture() ?: return@async ReaderViewModel.SetAsCoverResult.Error
                setNovelCustomCover.await(novel, ByteArrayInputStream(bytes))
                ReaderViewModel.SetAsCoverResult.Success
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                ReaderViewModel.SetAsCoverResult.Error
            }
        }.await()

    fun setChapterBookmark(chapterIds: List<Long>, bookmarked: Boolean) {
        val ids = expandToUnits(chapterIds.toSet(), groupStitch)
        // Kept in step so the sheet and the app bar cannot disagree about the chapter being read.
        if (currentChapterId in ids) this.bookmarked.value = bookmarked
        // On Main so quick toggles are saved in order, as manga's are
        viewModelScope.launch { chapterRepo.setBookmarkBulk(ids.toList(), bookmarked) }
    }

    /** Every source's copy of [chapterIds] the stored stitch places with them, themselves included, as
     *  the database holds them now. Just the chapters when the novel is ungrouped. */
    private suspend fun groupCopies(chapterIds: Set<Long>): List<NovelChapter> =
        groupPool().resolve(expandToUnits(chapterIds, groupStitch))

    /** Every chapter of the merge group's members, as the database holds them now. */
    private suspend fun groupPool(): List<NovelChapter> = memberIds.flatMap { chapterRepo.getByNovelId(it) }

    /** [ids] as rows, from this pool where it holds them, else read on their own: a chapter outside the
     *  members, such as one a group-scoped open put back. */
    private suspend fun List<NovelChapter>.resolve(ids: Iterable<Long>): List<NovelChapter> {
        val byId = associateBy { it.id }
        return ids.mapNotNull { byId[it] ?: chapterRepo.getById(it) }
    }

    /** The bar's answer, as the details list gives it: a bookmark on another source's copy counts. */
    private suspend fun isBookmarkedInGroup(
        chapterId: Long,
    ): Boolean = expandToUnits(setOf(chapterId), groupStitch).any {
        chapterRepo.getById(it)?.bookmark ==
            true
    }

    fun toggleBookmark() = setChapterBookmark(listOf(currentChapterId), !bookmarked.value)

    /** The novel [chapterId] belongs to, for the browser to save the chapter's text to. */
    suspend fun novelIdOf(chapterId: Long): Long? = chapterRepo.getById(chapterId)?.novelId

    /** The source [novelId] is read from, for the browser to send that source's headers. */
    suspend fun sourceIdOf(novelId: Long): String? = novelRepo.getById(novelId)?.source

    /** [chapterId]'s page on the source site, by its row, so a chapter that failed to open has one too. */
    suspend fun webUrlOf(chapterId: Long): String? =
        chapterRepo.getById(chapterId)?.let { webUrl(it.novelId, it.url) }

    /**
     * [chapter]'s page on the source site, or null when its source cannot be resolved (not installed) or
     * has no page (a local novel answers blank), which the web actions have to hide rather than open
     * empty. A chapter read from disk resolves its source here, since its open never needed one.
     */
    suspend fun webUrlFor(chapter: LoadedChapter): String? = webUrl(chapter.novelId, chapter.url)

    private suspend fun webUrl(novelId: Long, chapterUrl: String): String? =
        runCatchingCancellable { textLoader.resolveSource(novelId) }.getOrNull()
            ?.webUrl(chapterUrl, isNovel = false)
            ?.takeIf { it.isNotBlank() }

    /** Start, cancel or delete chapter downloads from the sheet. */
    fun downloadChapter(chapterIds: List<Long>, action: ChapterDownloadAction) {
        // The queue edit or delete outlives the reader, which is often closed straight after the tap, as on manga
        appScope.launch {
            val pooled = groupPool()
            val picked = pooled.resolve(chapterIds)
            if (picked.isEmpty()) return@launch
            val fetched = downloadTargets.of(picked, pooled + picked) { it.id }
            // The copies the rows count as downloaded, which follow this session's scope.
            downloadManager.runChapterAction(action, rowDownloadChapters(action, picked, fetched) { it.id }) {
                pooled.resolve(mergeScope.copiesOf(chapterIds.toSet(), groupStitch))
            }
        }
    }

    /**
     * Builds the order the reader pages in, by the rule read aloud also steps by: once per session, and again
     * [around] a chapter read aloud moved on to, which leaves the chapter on screen as it is until it loads.
     */
    private suspend fun resolveReadingOrder(around: Long = currentChapterId) {
        val order = orderResolver.resolve(novelId, around, sourceScoped)
        memberIds = order.memberIds
        groupStitch = order.stitch
        downloadTargets = order.downloadTargets
        order.copiesToOpen?.let { copiesToOpen = it }
        if (around == currentChapterId) currentChapterId = order.currentId
        gapPresent = order.gapPresent
        orderedIds = order.orderedIds
        forwardEligibleIds = order.forwardEligibleIds
        readingOrder = order
    }

    /** [shown] as the merge group answers for it, over [pooled], every member's chapters. */
    private fun groupFlags(
        pooled: List<NovelChapter>,
        shown: List<NovelChapter>,
        novels: Map<Long, Novel>,
    ) = GroupChapterFlags(mergeScope, pooled, shown, groupStitch, { it.id }, { it.read }, { it.bookmark }) {
        novelDownloadCache.downloadedChapterIds(pooled, novels)
    }

    /**
     * Both chapters a step from [chapterId] lands on; a forward one honours the skip settings. After a
     * scroll out of [departed], that chapter stays beside it, see [neighbourChapters].
     */
    private fun neighboursOf(chapterId: Long, departed: Long? = null): Neighbours {
        val (previous, next) =
            orderedIds.neighbourChapters(orderedIds.indexOf(chapterId), departed) { it in forwardEligibleIds }
        return Neighbours(previous, next)
    }

    /**
     * Makes [around] the current chapter's neighbours, then warms them and queues the download-ahead
     * window. With the lane held and the chapter already moved, since both read it.
     */
    private fun settleNeighbours(around: Neighbours) {
        chapterNeighbours.value = around
        warmNeighbour(around.next)
        // Only the window scrolls backwards into a chapter, and the forward warm above already serves
        // the next-chapter button, so this one is the only warm the setting decides.
        if (windowedReading()) warmNeighbour(around.previous)
        extendWindowForward()
        // Off the lane: it reads the whole group, and nothing on the window waits for it.
        viewModelScope.launchIO { maybeDownloadAhead() }
    }

    /** Whether seamless chapters is on, which decides whether the window holds more than one chapter. */
    private fun windowedReading() = novelPreferences.readerSeamlessChapters().get()

    /**
     * One speculative request per neighbour, so crossing into it needs no round trip and the source
     * is not hit harder than a reader moving through it would. Backwards as well as forwards, because
     * a window the reader can scroll up into has to already hold what is above. One already cached
     * needs nothing, and whoever asked publishes the window it is in.
     */
    private fun warmNeighbour(chapterId: Long?) {
        val id = chapterId ?: return
        if (htmlCache.containsKey(id)) return
        if (!warmFailures.mayRetryUnprompted(id, SystemClock.elapsedRealtime())) return
        if (!warmsInFlight.begin(id)) return
        viewModelScope.launchIO {
            try {
                val row = chapterRepo.getById(id) ?: error("Chapter not found: $id")
                // Processed again when a change replaced the settings meanwhile, from the text cache rather
                // than the source, since the reload that change ran skips a warm already running.
                do {
                    val generation = textGeneration
                    val cached = cacheIfCurrent(id, loadChapterHtml(row), generation)
                } while (!cached)
                warmFailures.remove(id)
                rebuildWindow()
            } catch (e: Throwable) {
                // Swallowing the cancellation would record a failure against a session that is gone,
                // and leaving on the last chapter of a novel would then look like a broken one.
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Failed to prefetch novel chapter $id" }
                warmFailures.record(id, SystemClock.elapsedRealtime(), with(context) { e.loadFailureMessage })
                // The window is republished so the renderer can offer a retry at the edge the reader
                // is about to reach, rather than the text simply stopping there.
                rebuildWindow()
            } finally {
                warmsInFlight.finish(id)
            }
        }
    }

    /** Caches [text] for [chapterId] if no change has replaced the settings it was processed by, [generation]'s. */
    private fun cacheIfCurrent(chapterId: Long, text: NovelChapterText, generation: Int): Boolean =
        synchronized(htmlCache) {
            (generation == textGeneration).also { current -> if (current) htmlCache[chapterId] = text }
        }

    private suspend fun rebuildWindow() = lane.withLock { publishWindow() }

    /** Publishes the chapter being read with whichever neighbours are warmed, so the renderer's window
     *  follows the reader. With the lane held, so the chapter, its neighbours and the generation it
     *  goes out under are all read from one moment. */
    private suspend fun publishWindow() {
        val current = chapter.value ?: return
        window.value = buildWindow(current, chapterNeighbours.value, openGeneration)
    }

    /**
     * The window around [current] with [around] as its neighbours, under [generation]. Only warmed
     * chapters go in: an entry the cache has dropped would otherwise need a fetch the renderer cannot
     * wait for. Reads the database and publishes nothing, so a caller can build it before moving any
     * state and leave everything as it was if this throws.
     */
    private suspend fun buildWindow(current: LoadedChapter, around: Neighbours, generation: Int): Window {
        if (!windowedReading()) return Window(generation, current.chapterId, listOf(current))
        val published = window.value.takeIf { it.generation == generation }?.chapters.orEmpty()
        val forward = NovelWindowReach.joinable(
            reach = forwardReach(around.next),
            progress = (if (current.chapterId == currentChapterId) progress.value else current.progress)
                .wholePercent.toInt(),
            threshold = novelPreferences.readerAutoLoadNextAt().get(),
            fits = current.chapterId in fitsOnScreen,
            alreadyHeld = { id -> published.any { it.chapterId == id } },
        )
        // Separate emissions reach the host as separate diffs, so ordering one diff (NovelWindowDiff)
        // cannot keep a chapter from arriving above before the ones below it do.
        val previous = around.previous?.takeIf { id ->
            NovelWindowReach.previousMayJoin(
                forward = forward,
                resolved = { it in htmlCache || it in warmFailures },
                alreadyHeld = published.any { it.chapterId == id },
            )
        }
        val ids = NovelWindowReach.windowIds(previous, current.chapterId, forward) { it in htmlCache }
        val chapters = ids.mapNotNull { id ->
            if (id == current.chapterId) return@mapNotNull current
            val text = htmlCache[id] ?: return@mapNotNull null
            chapterRepo.getById(id)?.toLoadedChapter(text)
        }
        return Window(
            generation = generation,
            anchorId = current.chapterId,
            chapters = chapters,
            failedPrevious = boundaryFailure(previous, chapters),
            // The first chapter of the reach still missing is the edge the reader will run into.
            failedNext = boundaryFailure(forward.firstOrNull { id -> chapters.none { it.chapterId == id } }, chapters),
            previousJoining = around.previous?.let { id -> chapters.none { it.chapterId == id } && id !in warmFailures }
                ?: false,
        )
    }

    /** Past [next] while each fits on one screen, see [NovelWindowReach]. */
    private fun forwardReach(next: Long?): List<Long> = NovelWindowReach.forward(
        next = next,
        after = ::chapterAfter,
        fitsOnScreen = { it in fitsOnScreen },
    )

    /** The chapter a forward step from [id] lands on, null when there is none to step to. */
    private fun chapterAfter(id: Long): Long? = readingOrder?.chapterAfter(id)

    /** Whether [id] ends the order, null outside it, where chapterAfter has no index to step from and would
     *  call anything the last. */
    private fun isLastInOrder(id: Long): Boolean? = if (id in orderedIds) chapterAfter(id) == null else null

    /** Warms whatever the reach needs that is not cached yet; a warm republishes the window itself. */
    private fun extendWindowForward() {
        if (!windowedReading()) return
        forwardReach(chapterNeighbours.value.next).forEach(::warmNeighbour)
    }

    /** A failure only counts at an edge the reader can actually reach: once the chapter is in the
     *  window it loaded on a later attempt, whatever an earlier one recorded. */
    private fun boundaryFailure(chapterId: Long?, chapters: List<LoadedChapter>): BoundaryFailure? {
        val id = chapterId ?: return null
        if (chapters.any { it.chapterId == id }) return null
        return warmFailures[id]?.let { BoundaryFailure(it.message, it.failedAtElapsedMs, id) }
    }

    /** Download-ahead from the chapter being read, by the rule read aloud downloads ahead by too. */
    private suspend fun maybeDownloadAhead() {
        readingOrder?.let { orderResolver.downloadAhead(it, currentChapterId, downloadManager) }
    }

    /** Marks the chapter the user skipped away from as read, forward only, when the setting is on.
     *  Outlives the session. Twin of ReaderViewModel.markChapterReadOnSkip, pinned by
     *  MarkReadOnSkipConformanceTest. */
    private fun markReadOnSkip(departedId: Long) {
        if (!novelPreferences.readerMarkReadOnSkip().get()) return
        val context = writeContext()
        progressSlot.launch {
            withContext(io) {
                val chapter = chapterRepo.getById(departedId) ?: return@withContext
                if (!chapter.read && !incognito.of(chapter.novelId)) progressSlot.markChapterRead(chapter, context)
            }
        }
    }

    private suspend fun loadChapterHtml(chapter: NovelChapter, fromSource: Boolean = false): NovelChapterText = try {
        textLoader.load(chapter, renderingMode.value, fromSource)
    } catch (e: EmptyChapterException) {
        throw Exception(context.stringResource(MR.strings.novel_chapter_empty), e)
    }

    override fun onCleared() {
        // As a held write did when it was this model's: the reader closing writes it now.
        flushProgress()
        // Behind that write on the lane, so a finish it makes is claimed while the hold still stands
        progressSlot.launch { finishHold.close() }
        // Read aloud goes on without the reader, which only stops lending it a surface.
        readAloudSession.unregister(readAloud)
    }
}

/** A chapter sheet's row, as the merge group's [flags] answer for it. */
internal fun NovelChapter.toReaderChapterRow(
    sourceNames: Map<Long, String>,
    queued: Map<Long, NovelDownload>,
    flags: GroupChapterFlags<NovelChapter>,
    numberOnly: Boolean,
    words: ChapterTitleWords,
    targets: DownloadTargets,
): ReaderChapterRow {
    // A row whose download fetches another source's copy follows that copy through the queue.
    val downloadState = downloadStateOf(targets.queuedFor(id, queued::get)?.state?.toDownloadState()) {
        flags.isDownloaded(this)
    }
    return ReaderChapterRow(
        id = id,
        title = chapterRowTitle(name, chapterNumber, numberOnly, words),
        subtitle = chapterSubtitle(sourceNames[novelId], scanlator),
        dateUpload = dateUpload,
        progress = ChapterProgress.Percent(lastTextProgress),
        read = flags.isRead(this),
        bookmark = flags.isBookmarked(this),
        downloadState = downloadState,
        // A novel chapter is one request, so there is no percentage to report while it runs.
        downloadProgress = 0,
        offersDownload = targets.offersDownload(id, downloadState),
    )
}

/** The chapter being read with its position, kept for a process kill. Its own key, not the launch extra the
 *  intent carries: a restored Activity is handed that same intent, naming the chapter it opened on. */
private const val SAVED_PLACE = "novel_chapter_position"

/** The novel and scope the reader opened in, which a follow can take from read aloud, kept for the same kill. */
private const val SAVED_VIEW = "novel_reader_view"

/** Chapters held in the forward-prefetch cache. Small: it exists to make one step instant, not to
 *  keep a session's reading in memory. */
private const val MAX_CACHED_CHAPTERS = 5
