package reikai.presentation.reader

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.expandToUnits
import reikai.domain.merge.withOpenedChapter
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.DeleteNovelChaptersBehindReader
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.model.readerOrientation
import reikai.domain.novel.model.readingOrderComparator
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.chaptersToDownloadAhead
import reikai.domain.reader.isChapterComplete
import reikai.domain.reader.isForwardEligible
import reikai.domain.reader.neighbourChapter
import reikai.domain.reader.readerChapterFilters
import reikai.domain.reader.removeDuplicateChapters
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.toDownloadState
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelChapterTextLoader
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.novel.reader.ReaderMargins
import reikai.presentation.reader.text.NovelChapterFinish
import reikai.presentation.reader.text.NovelLeaveRule
import reikai.presentation.reader.text.NovelOpenLanding
import reikai.presentation.reader.text.NovelResume
import reikai.presentation.reader.text.NovelWarmPolicy
import reikai.presentation.reader.text.NovelWindowReach
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The light-novel provider model under the shared reader host: reader display settings, and the raw
 * chapter HTML the viewport renders. Settings live in their own flow so changing one updates the
 * rendered chapter live instead of reloading it.
 */
@AssistedInject
class NovelReaderViewModel(
    @Assisted val novelId: Long,
    @Assisted val initialChapterId: Long,
    /** Source scope walks only [novelId]'s own chapters; group scope aggregates the merge group. */
    @Assisted val sourceScoped: Boolean,
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val novelPreferences: NovelPreferences,
    private val downloadManagerProvider: () -> NovelDownloadManager,
    private val upsertNovelHistory: UpsertNovelHistory,
    private val setNovelReadStatus: SetNovelReadStatus,
    // Merge-group resolution + the shared "mark duplicate read" pref, for marking a merged novel's
    // copies of a finished chapter read (parity with the manga reader).
    private val mergeManager: NovelMergeManager,
    private val mergedChapterProvider: NovelMergedChapterProvider,
    private val libraryPreferences: LibraryPreferences,
    private val trackNovelChapter: TrackNovelChapter,
    private val trackPreferences: TrackPreferences,
    private val getIncognitoState: GetIncognitoState,
    private val setNovelViewerFlags: SetNovelViewerFlags,
    private val novelDownloadCache: NovelDownloadCache,
    private val deleteChaptersBehindReader: DeleteNovelChaptersBehindReader,
    private val context: Context,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(novelId: Long, initialChapterId: Long, sourceScoped: Boolean): NovelReaderViewModel
    }

    // Building the manager restores the persisted queue and can start the download worker, so it is
    // resolved on first read rather than at construction, which keeps that off the opening thread.
    private val downloadManager: NovelDownloadManager get() = downloadManagerProvider()

    /** Session-scoped, so the source cache inside it lives exactly as long as this reading session. */
    private val textLoader = NovelChapterTextLoader(
        novelRepo = novelRepo,
        sourceManager = sourceManager,
        installer = installer,
        preferences = novelPreferences,
        readDownloaded = { novel, chapter -> downloadManager.getChapterText(novel, chapter) },
    )

    /** Captured whenever a chapter opens (mirrors ReaderViewModel). Global-only: novel sources are
     *  String-keyed with no installed extension, so per-source incognito (await(sourceId)) can't apply. */
    @Volatile
    private var incognitoMode: Boolean = false

    /** Owning novel of the current chapter. Defaults to the host (== owner for a standalone novel);
     *  a merged session re-points it per chapter so the web actions resolve the source being read. */
    @Volatile
    private var currentNovelId: Long = novelId

    /** When the current chapter began being read, for the novel-history session duration (the analog of
     *  ReaderViewModel.chapterReadStartTime). Reset whenever a different chapter loads. */
    @Volatile
    private var chapterReadStartTime: Long? = null

    /** Loading until the first chapter renders, so the host shows a spinner rather than a blank page.
     *  Declared above the init block that calls load(), which would otherwise write it before it exists. */
    val loadState = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Loading)

    /** The open chapter's bookmark state, seeded when it loads and flipped from the bar or the sheet.
     *  Above the init block for the same reason as [loadState]: the first load writes it. */
    private val bookmarkedState = MutableStateFlow(false)

    fun retryLoad() = load()

    /**
     * The reader asking again for the chapter whose failure is drawn at that edge of the window, which
     * need not be a neighbour: past a chapter that fits on screen, the edge is further on. Immediate:
     * waiting out a cooldown the reader has just overridden by hand is the stranding the cooldown
     * exists to prevent, in miniature.
     */
    fun retryBoundary(forward: Boolean) {
        val edge = windowState.value.let { if (forward) it.failedNext else it.failedPrevious } ?: return
        // Dropped without republishing the window: the renderer is already showing its own progress
        // for this tap, and clearing the edge here would take that away and put it back.
        warmFailures.remove(edge.chapterId)
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

    /** Finishes each chapter once a session, however often it completes. */
    private val chapterFinish = NovelChapterFinish(
        chapterRepo = chapterRepo,
        setNovelReadStatus = setNovelReadStatus,
        libraryPreferences = libraryPreferences,
        trackPreferences = trackPreferences,
        trackNovelChapter = trackNovelChapter,
        context = context,
    )

    /** Positions not written yet, the latest per chapter, so writes that run out of order store the
     *  newest rather than whichever ran last. */
    private val unwritten = ConcurrentHashMap<Long, Int>()
    private val writeLock = Mutex()

    /** Per-novel reader orientation override (a [ReaderOrientation] flagValue; 0 = follow the global
     *  default). Keyed on the opened entry [novelId] (the anchor for a merged novel), since orientation
     *  is a book-level preference like sort/filter rather than per-source progress. */
    private val orientationOverride = MutableStateFlow(ReaderOrientation.DEFAULT.flagValue)

    fun setOrientation(flagValue: Int) {
        orientationOverride.value = flagValue
        viewModelScope.launchIO { setNovelViewerFlags.awaitSetOrientation(novelId, flagValue.toLong()) }
    }

    fun setKeepScreenOn(enabled: Boolean) = novelPreferences.readerKeepScreenOn().set(enabled)

    fun setAutoScroll(enabled: Boolean) = novelPreferences.readerAutoScroll().set(enabled)

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
    val settings: StateFlow<NovelReaderSettings> = combine(
        // Split because the typed combine stops at five flows, not because the halves differ.
        combine(
            combine(
                novelPreferences.readerFontSize().changes(),
                novelPreferences.readerLineSpacing().changes(),
                novelPreferences.readerTextAlign().changes(),
                novelPreferences.readerFontFamily().changes(),
            ) { fontSize, lineHeight, textAlign, fontFamily ->
                TypePrefs(fontSize, lineHeight, textAlign, fontFamily)
            },
            combine(
                novelPreferences.readerMarginTop().changes(),
                novelPreferences.readerMarginBottom().changes(),
                novelPreferences.readerMarginLeft().changes(),
                novelPreferences.readerMarginRight().changes(),
            ) { top, bottom, left, right -> ReaderMargins(top, bottom, left, right) },
            novelPreferences.readerParagraphIndent().changes(),
            novelPreferences.readerParagraphSpacing().changes(),
        ) { type, margins, indent, spacing -> DisplayPrefs(type, margins, indent, spacing) },
        combine(
            novelPreferences.readerFollowSystemTheme().changes(),
            novelPreferences.readerBackgroundColor().changes(),
            novelPreferences.readerTextColor().changes(),
        ) { followSystem, bg, text -> ThemePrefs(followSystem, bg, text) },
        novelPreferences.readerKeepScreenOn().changes(),
        combine(
            orientationOverride,
            novelPreferences.readerDefaultOrientation().changes(),
        ) { override, default -> OrientationPrefs(override, default) },
        combine(
            combine(
                novelPreferences.readerTtsEnabled().changes(),
                novelPreferences.readerTtsRate().changes(),
                novelPreferences.readerTtsPitch().changes(),
                novelPreferences.readerTtsAutoPageAdvance().changes(),
                novelPreferences.readerTtsScrollToTop().changes(),
            ) { enabled, rate, pitch, autoAdvance, scrollTop ->
                TtsPrefs(enabled, rate, pitch, autoAdvance, scrollTop)
            },
            combine(
                novelPreferences.readerBionicReading().changes(),
                novelPreferences.readerRemoveExtraSpacing().changes(),
                novelPreferences.readerTapToScroll().changes(),
                novelPreferences.readerSwipeGestures().changes(),
                novelPreferences.readerShowProgressPercentage().changes(),
            ) { bionic, spacing, tapScroll, swipe, showProgress ->
                FlagPrefs(bionic, spacing, tapScroll, swipe, showProgress)
            },
            combine(
                novelPreferences.readerAutoScroll().changes(),
                novelPreferences.readerAutoScrollSpeed().changes(),
                novelPreferences.readerRailHeight().changes(),
                novelPreferences.readerRailOnLeft().changes(),
            ) { autoScroll, speed, railHeight, railOnLeft ->
                ScrollPrefs(autoScroll, speed, railHeight, railOnLeft)
            },
            combine(
                novelPreferences.readerUseVolumeButtons().changes(),
                novelPreferences.readerVolumeButtonsInverted().changes(),
                novelPreferences.readerVolumeButtonsFraction().changes(),
            ) { enabled, inverted, fraction -> VolumePrefs(enabled, inverted, fraction) },
            novelPreferences.readerAlwaysShowChapterTransition().changes(),
        ) { tts, flags, scroll, volume, alwaysShowTransition ->
            ReaderExtraPrefs(tts, flags, scroll, volume, alwaysShowTransition)
        },
    ) { display, theme, keepScreenOn, orient, extra ->
        NovelReaderSettings(
            fontSize = display.type.fontSize,
            lineHeight = display.type.lineHeight,
            textAlign = display.type.textAlign,
            margins = display.margins,
            paragraphIndent = display.paragraphIndent,
            paragraphSpacing = display.paragraphSpacing,
            fontFamily = display.type.fontFamily,
            followSystemTheme = theme.followSystem,
            backgroundColor = theme.background,
            textColor = theme.textColor,
            keepScreenOn = keepScreenOn,
            orientation = orient.override,
            resolvedOrientation = orient.resolved,
            ttsEnabled = extra.tts.enabled,
            ttsRate = extra.tts.rate,
            ttsPitch = extra.tts.pitch,
            ttsAutoPageAdvance = extra.tts.autoPageAdvance,
            ttsScrollToTop = extra.tts.scrollToTop,
            bionicReading = extra.flags.bionicReading,
            removeExtraSpacing = extra.flags.removeExtraSpacing,
            tapToScroll = extra.flags.tapToScroll,
            swipeGestures = extra.flags.swipeGestures,
            showProgressPercentage = extra.flags.showProgressPercentage,
            autoScroll = extra.scroll.autoScroll,
            autoScrollSpeed = extra.scroll.autoScrollSpeed,
            railHeightPercent = extra.scroll.railHeight,
            railOnLeft = extra.scroll.railOnLeft,
            useVolumeButtons = extra.volume.enabled,
            volumeButtonsInverted = extra.volume.inverted,
            volumeButtonsFraction = extra.volume.fraction,
            alwaysShowChapterTransition = extra.alwaysShowTransition,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, currentSettings())

    /** The chapter the viewport renders, or null while it is loading (or after a failed load). */
    data class LoadedChapter(
        val chapterId: Long,
        val title: String,
        /** The chapter path on its source, for the web actions. */
        val url: String,
        val html: String,
        val baseUrl: String?,
        val progressPercent: Int,
        /** What the marker between two chapters reads (`NovelSeam`): the number its missing-chapters
         *  count is taken from, and whether this chapter's own copy is on disk. */
        val chapterNumber: Double,
        val downloaded: Boolean,
        /** No chapter follows it to step forward to, the answer `chapterAfter` gives, so the end marker
         *  (`NovelSeam.end`) is drawn below it. */
        val isLast: Boolean,
    )

    /** The opened entry's own title, which a merged session keeps even as chapters cross sources. */
    internal val entryTitle = MutableStateFlow<String?>(null)

    private val loadedChapter = MutableStateFlow<LoadedChapter?>(null)

    /**
     * The chapter being read, which the renderer decides once it holds more than one: crossing a
     * boundary changes this without any load. Everything a user sees named or acted on (the title,
     * the bookmark, the web actions, the chapter list's mark) follows it rather than the load.
     */
    val chapter: StateFlow<LoadedChapter?> = loadedChapter

    private val windowState = MutableStateFlow(Window())

    /**
     * The chapters to render, in reading order: the one being read, the chapter before it, and the
     * forward reach of [NovelWindowReach] after it, each once it is warmed. The host starts a renderer
     * over on a new [Window.generation] through [landingOf] and reports back through [rendererLanded].
     */
    val window: StateFlow<Window> = windowState

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

    private val liveProgress = MutableStateFlow(0)

    /**
     * How far down the open chapter the reader is, as a whole percent. Reported on every scroll frame,
     * which is what the navigator follows.
     */
    val progressPercent: StateFlow<Int> = liveProgress

    /**
     * The generation the renderer has started on, which the host reports through [rendererLanded].
     * Until it is [openGeneration], what the renderer reports is about the window an open replaced,
     * and taking its word moved the reader back to the chapter they had just left.
     */
    @Volatile
    private var rendererGeneration = -1

    /** Which chapter may be read while the renderer settles on the one it started on. */
    @Volatile
    private var landing = NovelOpenLanding(initialChapterId) { false }

    /** The chapter the renderer named last, with the generation it was named in, which [cross] moves
     *  to. A report racing an open carries the old generation, so it cannot cross out of the open. */
    @Volatile
    private var visibleReport: Pair<Int, Long>? = null

    /** The position reported last, so a crossing shows the arriving chapter's own rather than the
     *  departed one's until the next report. */
    @Volatile
    private var latestReport: Pair<Long, Int>? = null

    /**
     * The anchor of [window], at where the reader is now. An Activity rebuilt mid-chapter renders the
     * same window again, and the position the chapter had when it was opened put the reader back there
     * and let the next save overwrite theirs. Within a chapter's last screen every position reads 100,
     * so a rebuild there lands its last line at the bottom: up to a screen back, never past text the
     * reader has not seen, which landing on the chapter below would do.
     */
    fun landingOf(window: Window): LoadedChapter {
        val anchor = window.chapters.first { it.chapterId == window.anchorId }
        return if (anchor.chapterId == currentChapterId) anchor.copy(progressPercent = liveProgress.value) else anchor
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

    private var progressSaveJob: Job? = null

    /** The reported position not yet written, with the chapter it belongs to: a step must not let a
     *  debounced write land on the chapter that replaced it. */
    @Volatile
    private var pendingSave: Pair<Long, Int>? = null

    /** Per chapter, since the first report of an arriving chapter is no repeat of the departed one's. */
    @Volatile
    private var lastSaved: Pair<Long, Int>? = null

    /**
     * Every scroll persists, debounced. Keying the write on a settle instead lost an auto-scrolled or
     * scrubbed read entirely, because those move the viewport without a touch to end.
     */
    fun reportProgress(id: Long, percent: Int) = takeReport(id, percent, settled = false)

    /** A renderer that knows its own scroll has settled, which the web layer reports on scrollend. */
    fun saveProgress(id: Long, percent: Int) = takeReport(id, percent, settled = true)

    private fun takeReport(id: Long, percent: Int, settled: Boolean) {
        if (!reportsCount()) return
        // A report for a chapter the window no longer holds is a straggler from a crossing, and
        // writing it would move a position the reader has already left behind.
        if (windowState.value.chapters.none { it.chapterId == id }) return
        val clamped = percent.coerceIn(0, 100)
        val wasLanding = !landing.settled
        if (!landing.counts(id, clamped)) return
        // The reader moved, so the chapter the renderer named while the landing held it now stands.
        if (wasLanding && landing.settled) requestCrossing()
        latestReport = id to clamped
        if (id == currentChapterId) liveProgress.value = clamped
        // Written here rather than left to the crossing, which happens on its own coroutine: the very
        // next report belongs to the arriving chapter and would otherwise land on the departed one.
        val pending = pendingSave
        if (pending != null && pending.first != id) {
            progressSaveJob?.cancel()
            pendingSave = null
            persistProgress(pending.first, pending.second)
        }
        if (!settled && lastSaved == id to clamped) return
        lastSaved = id to clamped
        pendingSave = id to clamped
        progressSaveJob?.cancel()
        // A settle is where the scroll stopped, and finishing carries mark-as-read, the sibling marking
        // and the tracker push, so neither waits.
        if (settled || clamped.completesChapter()) {
            flushProgress()
            return
        }
        progressSaveJob = viewModelScope.launchUI {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            flushProgress()
        }
    }

    /** Write the last reported position now. The host calls this on pause, so being backgrounded or
     *  killed cannot drop a debounced write. */
    fun flushProgress() {
        progressSaveJob?.cancel()
        val (id, percent) = pendingSave ?: return
        pendingSave = null
        persistProgress(id, percent)
    }

    /** The chapter on screen, which a merged session moves across sources. Every bar verb acts on this
     *  one, so it advances only once a chapter has actually rendered. */
    @Volatile
    private var currentChapterId: Long = initialChapterId

    /** The chapter a load is aiming at, which is what a retry repeats. Separate from [currentChapterId]
     *  because a load that fails leaves the reader showing what it had. */
    @Volatile
    private var pendingChapterId: Long = initialChapterId

    /** Every chapter this session can reach, in reading order, duplicates and hidden ones already gone. */
    @Volatile
    private var orderedIds: List<Long> = emptyList()

    /** Which of [orderedIds] a forward step may land on, per the skip settings. A back step ignores it,
     *  so the chapter just finished stays reachable from the one after it. */
    @Volatile
    private var forwardEligibleIds: Set<Long> = emptySet()

    /** The novels of the opened one's merge group, itself alone when ungrouped, and the group's stored
     *  stitch (empty when ungrouped). Resolved with [orderedIds], in either scope. */
    @Volatile
    private var memberIds: List<Long> = listOf(novelId)

    @Volatile
    private var groupStitch: List<ChapterUnit> = emptyList()

    private val neighbours = MutableStateFlow(Neighbours())

    /** What the navigator's chapter buttons enable on. */
    val chapterNeighbours: StateFlow<Neighbours> = neighbours

    data class Neighbours(val previous: Long? = null, val next: Long? = null)

    /** Chapters warmed by the forward prefetch, newest first, so a forward step renders without a
     *  round trip. Bounded, because a long session would otherwise hold every chapter it has read. */
    private val htmlCache: MutableMap<Long, Pair<String, String?>> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Pair<String, String?>>(MAX_CACHED_CHAPTERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<String, String?>>) =
                size > MAX_CACHED_CHAPTERS
        },
    )

    /** The last warm failure per chapter, which [NovelWarmPolicy] reads to decide whether the window
     *  may reach for that chapter again unprompted. An explicit open clears the lot. */
    private val warmFailures = NovelWarmPolicy.Failures()

    /** Chapters with a warm already running, so two crossings in quick succession do not fetch the
     *  same chapter twice. */
    private val warmsInFlight: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

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
        // Outside the order, chapterAfter has no index to step from and would call anything the last.
        if (chapterId !in orderedIds) return
        val chapter = windowState.value.chapters.firstOrNull { it.chapterId == chapterId } ?: return
        val hasNext = chapterAfter(chapterId) != null
        // A position it reported on opening, still waiting to be written, would land after the mark
        // and put a chapter just read back at 0.
        if (!hasNext && pendingSave?.first == chapterId) {
            progressSaveJob?.cancel()
            pendingSave = null
        }
        // Off the caller's thread, which for the native renderer is the main one, since this parses.
        viewModelScope.launchNonCancellable {
            if (!NovelLeaveRule.readsOnReachingEnd(hasNext, chapter.html)) return@launchNonCancellable
            if (chapterRepo.getById(chapterId)?.read != false) return@launchNonCancellable
            writeProgress(chapterId, 100)
        }
    }

    init {
        viewModelScope.launchIO {
            novelRepo.getById(novelId)?.let {
                orientationOverride.value = it.readerOrientation.toInt()
                entryTitle.value = it.title
            }
        }
        // The cache holds pipeline output, so a chapter-text setting reaches the open chapter and the
        // prefetched next one only by dropping both and running it again.
        textLoader.settingsChanged
            .onEach {
                htmlCache.clear()
                // Re-aimed at what is on screen: a seamless crossing moves the reader into a chapter
                // without opening it, so the load would otherwise reopen the one the session started
                // on. Not done in cross(), which would also redirect a retry.
                pendingChapterId = currentChapterId
                load()
            }
            .launchIn(viewModelScope)
        load()
    }

    /** Jump to [chapterId] from the chapter list. A no-op on the chapter already open. */
    fun open(chapterId: Long) {
        if (chapterId == currentChapterId && loadedChapter.value != null) return
        goTo(chapterId)
    }

    /** Forward only, so it is the step that can mark the departed chapter read. */
    fun nextChapter() = neighbours.value.next?.let { goTo(it, markDepartedRead = true) } ?: Unit

    fun previousChapter() = neighbours.value.previous?.let { goTo(it) } ?: Unit

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
        if (windowState.value.chapters.none { it.chapterId == chapterId }) return
        visibleReport = generation to chapterId
        if (landing.mayRead(chapterId)) requestCrossing()
    }

    /** The reader dragged the page or pressed a key that scrolls it, which the host sees first. The
     *  landing settles on the next report that moved, not here: a drag can scroll nothing. */
    fun readerMoved() = landing.readerMoved()

    /**
     * Non-cancellable, since the chapters a forward crossing passes are finished only here, and leaving
     * the reader mid-fling is when the last crossing is still waiting for the lane.
     */
    private fun requestCrossing() {
        viewModelScope.launchNonCancellable {
            lane.withLock { cross() }.forEach { writeProgress(it, 100) }
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
        val arriving = windowState.value.chapters.firstOrNull { it.chapterId == target } ?: return emptyList()
        val passed = NovelLeaveRule.passedGoingForward(
            window = windowState.value.chapters.map { it.chapterId },
            from = currentChapterId,
            to = target,
        )
        // Read before anything moves, so a failure leaves the session on the chapter it was reading.
        val owner = chapterRepo.getById(target)?.novelId ?: currentNovelId
        val bookmarked = isBookmarkedInGroup(target)
        val around = neighboursOf(target)
        // The window re-centres on where the reader now is, which warms the chapter beyond and
        // lets the one two behind go.
        val recentred = buildWindow(arriving, around, openGeneration)
        // A partial position still pending for a chapter being read in full would race the mark.
        if (pendingSave?.first in passed) {
            progressSaveJob?.cancel()
            pendingSave = null
        }
        flushProgress()
        updateHistory()
        currentChapterId = target
        currentNovelId = owner
        chapterReadStartTime = System.currentTimeMillis()
        bookmarkedState.value = bookmarked
        loadedChapter.value = arriving
        // Its own first report usually beat this here, and was not the current chapter's when it came.
        liveProgress.value = latestReport?.takeIf { it.first == target }?.second ?: arriving.progressPercent
        settleNeighbours(around)
        windowState.value = recentred
        return passed
    }

    private fun goTo(chapterId: Long, markDepartedRead: Boolean = false) {
        viewModelScope.launchIO {
            // The departed chapter is stamped into history before the switch, and marked read while it
            // and its owning novel are still the current ones. Its pending position is written first,
            // or the debounce would still be waiting when the chapter it belongs to stops being current.
            // With the lane held, so a crossing cannot stamp the same stretch of reading again or move
            // the chapter the mark is for.
            lane.withLock {
                flushProgress()
                updateHistory()
                if (markDepartedRead) {
                    // One that fit on the screen was read in full, whatever the skip setting says: it has
                    // no scroll room, so this step is the only point it can be called finished.
                    if (currentChapterId in fitsOnScreen) {
                        persistProgress(currentChapterId, 100)
                    } else {
                        markReadOnSkip(currentChapterId)
                    }
                }
                pendingChapterId = chapterId
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
        val target = pendingChapterId
        // An explicit open is the reader asking for chapters afresh, so nothing a previous warm
        // recorded may go on suppressing one. Without this the window strands on a chapter that has
        // since recovered, which is the bug tsundoku's own latch shipped with.
        warmFailures.clear()
        loadState.value = ReaderLoadState.Loading
        viewModelScope.launchIO {
            try {
                incognitoMode = getIncognitoState.await(null)
                if (orderedIds.isEmpty()) resolveReadingOrder()
                val row = chapterRepo.getById(target) ?: error("Chapter not found: $target")
                val (html, baseUrl) = htmlCache[row.id] ?: loadChapterHtml(row).also { htmlCache[row.id] = it }
                val bookmarked = isBookmarkedInGroup(row.id)
                lane.withLock {
                    // A later open overtook this one while it loaded, and committing it now would put
                    // the reader back on the chapter they had moved on from.
                    if (target != pendingChapterId) return@launchIO
                    commitOpen(row, html, baseUrl, bookmarked)
                }
                loadState.value = ReaderLoadState.Idle
            } catch (e: Throwable) {
                // Leaving the reader cancels this scope, and swallowing that would report a load
                // failure for a chapter nobody is waiting for any more.
                if (e is CancellationException) throw e
                if (target != pendingChapterId) return@launchIO
                logcat(LogPriority.ERROR, e) { "Failed to load novel chapter $target" }
                // A failed step stamped the chapter it left into history, and the reader goes on in it.
                if (loadedChapter.value != null && chapterReadStartTime == null) restartReadTimer()
                loadState.value = ReaderLoadState.Failed(e.message, canKeepReading = loadedChapter.value != null)
            }
        }
    }

    /**
     * Makes [row] the chapter being read, with the lane held. Everything that reads or can throw runs
     * before any state moves, since the reader goes on rendering the chapter it already had: moving
     * first pointed the bookmark and the web actions at a chapter that never appeared, or raised a
     * generation no window was published under, which closed the renderer's reports for good.
     */
    private suspend fun commitOpen(row: NovelChapter, html: String, baseUrl: String?, bookmarked: Boolean) {
        val opened = row.toLoadedChapter(html, baseUrl)
        val around = neighboursOf(row.id)
        // A new generation tells the renderer to start over, and it closes reports until it has.
        val window = buildWindow(opened, around, openGeneration + 1)
        // The window being replaced takes its reader's last position with it.
        flushProgress()
        // A reload of the chapter already open, after a chapter-text setting changed, keeps its timer
        // and its place: restarting either lost what the reader had done since it opened.
        val reloading = row.id == currentChapterId && loadedChapter.value != null
        if (!reloading || chapterReadStartTime == null) chapterReadStartTime = System.currentTimeMillis()
        currentChapterId = row.id
        currentNovelId = row.novelId
        bookmarkedState.value = bookmarked
        loadedChapter.value = opened
        if (!reloading) liveProgress.value = opened.progressPercent
        openGeneration = window.generation
        visibleReport = openGeneration to opened.chapterId
        settleNeighbours(around)
        windowState.value = window
    }

    private suspend fun NovelChapter.toLoadedChapter(html: String, baseUrl: String?) = LoadedChapter(
        chapterId = id,
        title = name,
        url = url,
        html = html,
        baseUrl = baseUrl,
        progressPercent = NovelResume.percent(read, lastTextProgress),
        chapterNumber = chapterNumber,
        // This copy's own, as manga's transition reads the chapter it will load rather than the group's.
        downloaded = novelRepo.getById(novelId)?.let { novelDownloadCache.isChapterDownloaded(it, this) } == true,
        // Outside the order, chapterAfter has no index to step from and would call anything the last.
        isLast = id in orderedIds && chapterAfter(id) == null,
    )

    /**
     * Persist the reader's scroll position for [id]. The renderers report a whole percent (0..100);
     * store it as 0..10000 to match [NovelChapter.lastTextProgress]. Reaching the end auto-marks read.
     * Outlives the session, as manga's progress writes do: leaving the reader is exactly when the last
     * position and a finish just reached arrive.
     */
    private fun persistProgress(id: Long, clamped: Int) {
        if (incognitoMode) return
        unwritten[id] = clamped
        viewModelScope.launchNonCancellable { writeUnwritten(id, clamped) }
    }

    /** [persistProgress] on the caller's own coroutine, for one already outliving the session: a
     *  launch into a scope that has ended never starts. */
    private suspend fun writeProgress(id: Long, clamped: Int) {
        if (incognitoMode) return
        unwritten[id] = clamped
        writeUnwritten(id, clamped)
    }

    private suspend fun writeUnwritten(id: Long, clamped: Int) {
        val chapter = writeLock.withLock {
            unwritten.remove(id)?.let { chapterRepo.setLastTextProgress(id, it * 100L) }
            // Fetched before marking, so the shared interactor still sees it unread.
            chapterRepo.getById(id)
        } ?: return
        // Stamp the owning novel's last-read time so the LastRead library sort reflects this read.
        novelRepo.setLastReadAt(chapter.novelId, System.currentTimeMillis())
        if (clamped.completesChapter()) markChapterRead(chapter)
    }

    /**
     * Finishing [chapter] through [NovelChapterFinish], the legacy reader's path too. Reaching the end
     * and mark-read-on-skip both land here, as manga's both go through updateChapterProgressOnComplete.
     * The trim is manga's ReaderViewModel.deleteChapterIfNeeded, at the same point.
     */
    private suspend fun markChapterRead(chapter: NovelChapter) {
        chapterFinish.finish(chapter, memberIds, groupStitch) {
            deleteChaptersBehindReader.await(chapter.novelId, orderedIds, chapter.id)
        }
    }

    /** Stamp the current chapter into novel history and accumulate this session's read time. Called on
     *  chapter switch and on leaving the reader (the novel twin of ReaderViewModel.updateHistory). */
    suspend fun updateHistory() {
        if (incognitoMode) return
        val id = loadedChapter.value?.chapterId ?: return
        val now = System.currentTimeMillis()
        val duration = chapterReadStartTime?.let { now - it } ?: 0L
        upsertNovelHistory.await(NovelHistoryUpdate(id, now, duration))
        chapterReadStartTime = null
    }

    /** The host calls this on resume, since leaving the reader stamped [updateHistory] and stopped the
     *  clock. The twin of ReaderViewModel.restartReadTimer. */
    fun restartReadTimer() {
        chapterReadStartTime = System.currentTimeMillis()
    }

    private fun currentMargins() = ReaderMargins(
        top = novelPreferences.readerMarginTop().get(),
        bottom = novelPreferences.readerMarginBottom().get(),
        left = novelPreferences.readerMarginLeft().get(),
        right = novelPreferences.readerMarginRight().get(),
    )

    private fun currentSettings(): NovelReaderSettings {
        val override = orientationOverride.value
        val default = novelPreferences.readerDefaultOrientation().get()
        return NovelReaderSettings(
            fontSize = novelPreferences.readerFontSize().get(),
            lineHeight = novelPreferences.readerLineSpacing().get(),
            textAlign = novelPreferences.readerTextAlign().get(),
            margins = currentMargins(),
            paragraphIndent = novelPreferences.readerParagraphIndent().get(),
            paragraphSpacing = novelPreferences.readerParagraphSpacing().get(),
            fontFamily = novelPreferences.readerFontFamily().get(),
            followSystemTheme = novelPreferences.readerFollowSystemTheme().get(),
            backgroundColor = novelPreferences.readerBackgroundColor().get(),
            textColor = novelPreferences.readerTextColor().get(),
            keepScreenOn = novelPreferences.readerKeepScreenOn().get(),
            orientation = override,
            resolvedOrientation = OrientationPrefs(override, default).resolved,
            ttsEnabled = novelPreferences.readerTtsEnabled().get(),
            ttsRate = novelPreferences.readerTtsRate().get(),
            ttsPitch = novelPreferences.readerTtsPitch().get(),
            ttsAutoPageAdvance = novelPreferences.readerTtsAutoPageAdvance().get(),
            ttsScrollToTop = novelPreferences.readerTtsScrollToTop().get(),
            bionicReading = novelPreferences.readerBionicReading().get(),
            removeExtraSpacing = novelPreferences.readerRemoveExtraSpacing().get(),
            tapToScroll = novelPreferences.readerTapToScroll().get(),
            swipeGestures = novelPreferences.readerSwipeGestures().get(),
            showProgressPercentage = novelPreferences.readerShowProgressPercentage().get(),
            autoScroll = novelPreferences.readerAutoScroll().get(),
            autoScrollSpeed = novelPreferences.readerAutoScrollSpeed().get(),
            railHeightPercent = novelPreferences.readerRailHeight().get(),
            railOnLeft = novelPreferences.readerRailOnLeft().get(),
            useVolumeButtons = novelPreferences.readerUseVolumeButtons().get(),
            volumeButtonsInverted = novelPreferences.readerVolumeButtonsInverted().get(),
            volumeButtonsFraction = novelPreferences.readerVolumeButtonsFraction().get(),
            alwaysShowChapterTransition = novelPreferences.readerAlwaysShowChapterTransition().get(),
        )
    }

    /**
     * The chapter sheet's rows, in reading order, each showing the merge group's read, bookmarked and
     * on-disk state as the details list does. Cold, so the list is only built while the sheet is open,
     * and re-emitted as downloads move or the reader changes chapter. Read fresh rather than from the
     * session's opening, so a chapter this session marked shows as marked.
     */
    val chapterRows: Flow<List<ReaderChapterRow>> = flow {
        if (orderedIds.isEmpty()) resolveReadingOrder()
        val pooled = memberIds.flatMap { chapterRepo.getByNovelId(it) }
        val byId = pooled.associateBy { it.id }
        val chapters = orderedIds.mapNotNull { id -> byId[id] ?: chapterRepo.getById(id) }
        val sourceNames = chapterSourceNames(chapters)
        val novels = novelsOf(pooled + chapters)
        emitAll(
            combine(downloadManager.queueState, loadedChapter) { queue, _ ->
                val flags = groupFlags(pooled, chapters, novels)
                val queued = queue.associateBy { it.chapterId }
                chapters.map { it.toReaderChapterRow(sourceNames, queued, flags) }
            },
        )
    }.flowOn(Dispatchers.IO)

    /** Per-source display names keyed by novelId, for a merged novel's source labels. Empty for a
     *  single-source novel, so no label is drawn. */
    private suspend fun chapterSourceNames(chapters: List<NovelChapter>): Map<Long, String> {
        val novelIds = chapters.map { it.novelId }.distinct()
        if (novelIds.size <= 1) return emptyMap()
        return novelIds.associateWith { id ->
            textLoader.cachedSource(id)?.name
                ?: novelRepo.getById(id)?.source?.let { sourceManager.get(it)?.name ?: it }
                ?: ""
        }
    }

    /** Reaches every source's copy of the chapter, as the manga sheet and the details list do. */
    fun setChapterRead(chapterId: Long, read: Boolean) {
        viewModelScope.launchIO {
            val copies = groupCopies(chapterId)
            // Unmarked, it can be finished again, and finishing is what reaches the trackers.
            if (!read) chapterFinish.release(copies.map { it.id })
            setNovelReadStatus.await(read, copies)
        }
    }

    fun setChapterBookmark(chapterId: Long, bookmarked: Boolean) {
        val ids = expandToUnits(setOf(chapterId), groupStitch)
        // Kept in step so the sheet and the app bar cannot disagree about the chapter being read.
        if (currentChapterId in ids) bookmarkedState.value = bookmarked
        viewModelScope.launchIO { ids.forEach { chapterRepo.setBookmark(it, bookmarked) } }
    }

    /** Every source's copy of [chapterId] the stored stitch places with it, itself included, as the
     *  database holds them now. Just the chapter when the novel is ungrouped. */
    private suspend fun groupCopies(chapterId: Long): List<NovelChapter> =
        expandToUnits(setOf(chapterId), groupStitch).mapNotNull { chapterRepo.getById(it) }

    /** The bar's answer, as the details list gives it: a bookmark on another source's copy counts. */
    private suspend fun isBookmarkedInGroup(chapterId: Long): Boolean = groupCopies(chapterId).any { it.bookmark }

    val bookmarked: StateFlow<Boolean> = bookmarkedState

    fun toggleBookmark() = setChapterBookmark(currentChapterId, !bookmarkedState.value)

    /**
     * [chapter]'s page on the source site, or null for one read from disk whose source this session
     * never resolved, which is the case the web actions have to hide rather than open empty.
     */
    fun webUrlFor(chapter: LoadedChapter): String? =
        textLoader.cachedSource(currentNovelId)?.webUrl(chapter.url)

    /** Start, cancel or delete a chapter download from the sheet, mirroring the details model. */
    fun downloadChapter(chapterId: Long, action: ChapterDownloadAction) {
        viewModelScope.launchIO {
            val chapter = chapterRepo.getById(chapterId) ?: return@launchIO
            when (action) {
                ChapterDownloadAction.START -> downloadManager.downloadChapters(listOf(chapter))
                ChapterDownloadAction.START_NOW -> {
                    downloadManager.downloadChapters(listOf(chapter))
                    downloadManager.startDownloadNow(chapter.id)
                }
                ChapterDownloadAction.CANCEL -> downloadManager.cancelDownloads(listOf(chapter.id))
                // The row reads as downloaded when any source's copy is on disk, so every copy goes.
                ChapterDownloadAction.DELETE -> downloadManager.deleteChapters(groupCopies(chapterId))
            }
        }
    }

    /**
     * Builds the order the reader pages in, once per session. Source scope walks the opened novel's own
     * chapters; group scope aggregates the merge group, so History, Updates and the library need not
     * pass a list. A group-scoped chapter the unified list does not show is put back through
     * [withOpenedChapter], the kernel the manga reader shares, rather than leaving prev and next with
     * nowhere to step from.
     */
    private suspend fun resolveReadingOrder() {
        // Both scopes need the group behind the opened novel: source scope shows one source's rows, but
        // whether the story has been read is not a property of the row, so the flags are resolved here
        // once and the two members are loaded once.
        val ids = mergeManager.relatedIdsList(novelId)
        val pooled = if (ids.size <= 1) emptyList() else ids.flatMap { chapterRepo.getByNovelId(it) }
        val stitch = if (pooled.isEmpty()) emptyList() else mergedChapterProvider.stitchOf(novelId)
        memberIds = ids.ifEmpty { listOf(novelId) }
        groupStitch = stitch
        // The chapters stay chapters through both filters. Reducing to ids here meant re-reading every
        // one of them back out of the database a row at a time, before the first page could be drawn.
        val chapters = if (sourceScoped) {
            chapterRepo.getByNovelId(novelId)
        } else {
            val listed = if (pooled.isEmpty()) {
                chapterRepo.getByNovelId(novelId)
            } else {
                mergedChapterProvider.merged(pooled, stitch)
            }
            withOpenedChapter(
                unified = listed,
                opened = listed.find { it.id == currentChapterId }
                    ?: pooled.find { it.id == currentChapterId }
                    ?: chapterRepo.getById(currentChapterId),
                stitch = stitch,
                id = { it.id },
                // A novel source lists its chapters oldest-first, so the merged list runs that way too.
                byNumber = compareBy { it.chapterNumber },
                restamp = { chapter, order -> chapter.copy(sourceOrder = order) },
            )
        }
        val visible = filterHiddenChapters(dedupIfEnabled(chapters.sortedWith(readingOrder())))
        orderedIds = visible.map { it.id }
        val members = pooled.ifEmpty { visible }
        forwardEligibleIds = resolveForwardEligible(visible, groupFlags(members, visible, novelsOf(members)))
    }

    /** The opened novel's own chapter sort, always ascending, so paging follows the order the user chose
     *  on its chapter list. The manga reader resolves the same way. */
    private suspend fun readingOrder(): Comparator<NovelChapter> {
        val novel = novelRepo.getById(novelId)
        return if (novel == null) compareBy { it.chapterNumber } else readingOrderComparator(novel, novelPreferences)
    }

    /**
     * Drops same-numbered duplicates from the list rather than stepping over them, so the chapter list,
     * download-ahead and delete-after-read all count what the reader actually shows. Within one novel
     * only, which makes the origin tie-break meaningless here: every chapter of it has the same source.
     */
    private fun dedupIfEnabled(chapters: List<NovelChapter>): List<NovelChapter> {
        if (!novelPreferences.readerSkipDuplicateChapters().get()) return chapters
        val current = chapters.find { it.id == currentChapterId } ?: return chapters
        return chapters.removeDuplicateChapters(
            current,
            numberOf = { it.chapterNumber },
            idOf = { it.id },
            originOf = { null },
            ownerOf = { it.novelId },
        )
    }

    /** Drops user-hidden chapters so paging matches the details list. The open chapter is always kept,
     *  so opening a hidden one directly still resolves. The key mirrors the details screen. */
    private suspend fun filterHiddenChapters(chapters: List<NovelChapter>): List<NovelChapter> {
        val hidden = novelPreferences.hiddenChapters().get()
        if (hidden.isEmpty()) return chapters
        val sourceIdByNovel = HashMap<Long, String>()
        return chapters.filter { chapter ->
            if (chapter.id == currentChapterId) return@filter true
            val sourceId = sourceIdByNovel.getOrPut(chapter.novelId) {
                novelRepo.getById(chapter.novelId)?.source.orEmpty()
            }
            "$sourceId|${chapter.url}" !in hidden
        }
    }

    /** Which chapters a forward step may stop on, per the skip settings and this novel's own chapter-list
     *  filters. The open chapter stays eligible either way. */
    private suspend fun resolveForwardEligible(
        chapters: List<NovelChapter>,
        flags: GroupChapterFlags<NovelChapter>,
    ): Set<Long> {
        val skipRead = novelPreferences.readerSkipRead().get()
        val skipFiltered = novelPreferences.readerSkipFiltered().get()
        if (!skipRead && !skipFiltered) return chapters.mapTo(HashSet()) { it.id }
        val novel = novelRepo.getById(novelId) ?: return chapters.mapTo(HashSet()) { it.id }
        val filters = novel.readerChapterFilters(novelPreferences)
        return chapters.filterTo(HashSet()) { ch ->
            ch.id == currentChapterId || flags.isForwardEligible(ch, skipRead, skipFiltered, filters)
        }.mapTo(HashSet()) { it.id }
    }

    /** [shown] as the merge group answers for it, over [pooled], every member's chapters. */
    private fun groupFlags(
        pooled: List<NovelChapter>,
        shown: List<NovelChapter>,
        novels: Map<Long, Novel>,
    ) = GroupChapterFlags(pooled, shown, groupStitch, { it.id }, { it.read }, { it.bookmark }) {
        downloadedChapterIds(pooled, novels)
    }

    private suspend fun novelsOf(chapters: List<NovelChapter>): Map<Long, Novel> =
        chapters.map { it.novelId }.distinct().mapNotNull { novelRepo.getById(it) }.associateBy { it.id }

    /** Grouped by novel so the cache resolves each novel's download folder once rather than per chapter,
     *  which is what a merged group's long list would otherwise pay for on every queue change. */
    private fun downloadedChapterIds(chapters: List<NovelChapter>, novels: Map<Long, Novel>): Set<Long> =
        chapters
            .groupBy { it.novelId }
            .flatMapTo(HashSet()) { (novelId, owned) ->
                novels[novelId]?.let { novelDownloadCache.downloadedChapterIds(it, owned) }.orEmpty()
            }

    /** Both chapters a step from [chapterId] lands on; a forward one honours the skip settings. */
    private fun neighboursOf(chapterId: Long): Neighbours {
        val index = orderedIds.indexOf(chapterId)
        return Neighbours(
            previous = orderedIds.neighbourChapter(index, forward = false) { it in forwardEligibleIds },
            next = orderedIds.neighbourChapter(index, forward = true) { it in forwardEligibleIds },
        )
    }

    /**
     * Makes [around] the current chapter's neighbours, then warms them and queues the download-ahead
     * window. With the lane held and the chapter already moved, since both read it.
     */
    private fun settleNeighbours(around: Neighbours) {
        neighbours.value = around
        warmNeighbour(around.next)
        // Only the window scrolls backwards into a chapter, and the forward warm above already serves
        // the next-chapter button, so this one is the only warm the setting decides.
        if (windowedReading()) warmNeighbour(around.previous)
        extendWindowForward()
        // Off the lane: it reads the whole group, and nothing on the window waits for it.
        viewModelScope.launchIO { maybeDownloadAhead() }
    }

    /**
     * Whether the renderer holds more than one chapter, which decides how wide a window to publish.
     * The legacy standalone reader is the one that cannot, so it would only pay for neighbours it
     * has nowhere to draw; the two renderers on the shared host both hold a window.
     */
    private fun windowedReading() = novelPreferences.readerSeamlessChapters().get() &&
        novelPreferences.readerRenderingMode().get() != NovelRenderingMode.LEGACY

    /**
     * One speculative request per neighbour, so crossing into it needs no round trip and the source
     * is not hit harder than a reader moving through it would. Backwards as well as forwards, because
     * a window the reader can scroll up into has to already hold what is above. One already cached
     * needs nothing, and whoever asked publishes the window it is in.
     */
    private fun warmNeighbour(chapterId: Long?) {
        val id = chapterId ?: return
        if (htmlCache.containsKey(id)) return
        if (!warmFailures.mayAutoWarm(id, SystemClock.elapsedRealtime())) return
        if (!warmsInFlight.add(id)) return
        viewModelScope.launchIO {
            try {
                val row = chapterRepo.getById(id) ?: error("Chapter not found: $id")
                htmlCache[id] = loadChapterHtml(row)
                warmFailures.remove(id)
                rebuildWindow()
            } catch (e: Throwable) {
                // Swallowing the cancellation would record a failure against a session that is gone,
                // and leaving on the last chapter of a novel would then look like a broken one.
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Failed to prefetch novel chapter $id" }
                warmFailures.record(id, SystemClock.elapsedRealtime(), e.message)
                // The window is republished so the renderer can offer a retry at the edge the reader
                // is about to reach, rather than the text simply stopping there.
                rebuildWindow()
            } finally {
                warmsInFlight.remove(id)
            }
        }
    }

    private suspend fun rebuildWindow() = lane.withLock { publishWindow() }

    /** Publishes the chapter being read with whichever neighbours are warmed, so the renderer's window
     *  follows the reader. With the lane held, so the chapter, its neighbours and the generation it
     *  goes out under are all read from one moment. */
    private suspend fun publishWindow() {
        val current = loadedChapter.value ?: return
        windowState.value = buildWindow(current, neighbours.value, openGeneration)
    }

    /**
     * The window around [current] with [around] as its neighbours, under [generation]. Only warmed
     * chapters go in: an entry the cache has dropped would otherwise need a fetch the renderer cannot
     * wait for. Reads the database and publishes nothing, so a caller can build it before moving any
     * state and leave everything as it was if this throws.
     */
    private suspend fun buildWindow(current: LoadedChapter, around: Neighbours, generation: Int): Window {
        if (!windowedReading()) return Window(generation, current.chapterId, listOf(current))
        val forward = forwardReach(around.next)
        val published = windowState.value.takeIf { it.generation == generation }?.chapters.orEmpty()
        // Separate emissions reach the host as separate diffs, so ordering one diff (NovelWindowDiff)
        // cannot keep a chapter from arriving above before the ones below it do.
        val previous = around.previous?.takeIf { id ->
            NovelWindowReach.previousMayJoin(
                forward = forward,
                resolved = { it in htmlCache || it in warmFailures },
                alreadyHeld = published.any { it.chapterId == id },
            )
        }
        val ids = listOfNotNull(previous, current.chapterId) + forward
        val chapters = ids.mapNotNull { id ->
            if (id == current.chapterId) return@mapNotNull current
            val (html, baseUrl) = htmlCache[id] ?: return@mapNotNull null
            chapterRepo.getById(id)?.toLoadedChapter(html, baseUrl)
        }
        return Window(
            generation = generation,
            anchorId = current.chapterId,
            chapters = chapters,
            failedPrevious = boundaryFailure(previous, chapters),
            // The first chapter of the reach still missing is the edge the reader will run into.
            failedNext = boundaryFailure(forward.firstOrNull { id -> chapters.none { it.chapterId == id } }, chapters),
        )
    }

    /** Past [next] while each fits on one screen, see [NovelWindowReach]. */
    private fun forwardReach(next: Long?): List<Long> = NovelWindowReach.forward(
        next = next,
        after = ::chapterAfter,
        fitsOnScreen = { it in fitsOnScreen },
    )

    /** The chapter a forward step from [id] lands on, null when there is none to step to. */
    private fun chapterAfter(id: Long): Long? =
        orderedIds.neighbourChapter(orderedIds.indexOf(id), forward = true) { it in forwardEligibleIds }

    /** Warms whatever the reach needs that is not cached yet; a warm republishes the window itself. */
    private fun extendWindowForward() {
        if (!windowedReading()) return
        forwardReach(neighbours.value.next).forEach(::warmNeighbour)
    }

    /** A failure only counts at an edge the reader can actually reach: once the chapter is in the
     *  window it loaded on a later attempt, whatever an earlier one recorded. */
    private fun boundaryFailure(chapterId: Long?, chapters: List<LoadedChapter>): BoundaryFailure? {
        val id = chapterId ?: return null
        if (chapters.any { it.chapterId == id }) return null
        return warmFailures[id]?.let { BoundaryFailure(it.message, it.failedAtElapsedMs, id) }
    }

    /** Enqueues the next N unread, un-downloaded chapters in reading order, the novel twin of manga's
     *  autoDownloadWhileReading, pinned to it by [chaptersToDownloadAhead]. Off in incognito and when
     *  the setting is zero. Read fresh, so a chapter finished in this session is not queued again. */
    private suspend fun maybeDownloadAhead() {
        if (incognitoMode) return
        val ahead = novelPreferences.autoDownloadWhileReading().get()
        if (ahead <= 0) return
        val index = orderedIds.indexOf(currentChapterId)
        if (index < 0) return
        val pooled = memberIds.flatMap { chapterRepo.getByNovelId(it) }
        val byId = pooled.associateBy { it.id }
        val candidates = orderedIds.drop(index + 1).mapNotNull { byId[it] ?: chapterRepo.getById(it) }
        val novels = novelsOf(pooled + candidates)
        val flags = groupFlags(pooled, candidates, novels)
        val toDownload = chaptersToDownloadAhead(candidates, from = 0, count = ahead, isRead = flags::isRead)
            .filterNot { ch -> novels[ch.novelId]?.let { downloadManager.isChapterDownloaded(it, ch) } == true }
        if (toDownload.isNotEmpty()) downloadManager.downloadChapters(toDownload)
    }

    /** Marks the chapter the user skipped away from as read, forward only, when the setting is on.
     *  Outlives the session, as ReaderViewModel.markChapterReadOnSkip does. */
    private fun markReadOnSkip(departedId: Long) {
        if (incognitoMode || !novelPreferences.readerMarkReadOnSkip().get()) return
        viewModelScope.launchNonCancellable {
            val chapter = chapterRepo.getById(departedId) ?: return@launchNonCancellable
            if (!chapter.read) markChapterRead(chapter)
        }
    }

    suspend fun loadChapterHtml(chapter: NovelChapter): Pair<String, String?> = textLoader.load(chapter)

    private data class TypePrefs(
        val fontSize: Int,
        val lineHeight: Float,
        val textAlign: String,
        val fontFamily: String,
    )
    private data class DisplayPrefs(
        val type: TypePrefs,
        val margins: ReaderMargins,
        val paragraphIndent: Float,
        val paragraphSpacing: Float,
    )
    private data class ThemePrefs(val followSystem: Boolean, val background: String, val textColor: String)
    private data class TtsPrefs(
        val enabled: Boolean,
        val rate: Float,
        val pitch: Float,
        val autoPageAdvance: Boolean,
        val scrollToTop: Boolean,
    )
    private data class FlagPrefs(
        val bionicReading: Boolean,
        val removeExtraSpacing: Boolean,
        val tapToScroll: Boolean,
        val swipeGestures: Boolean,
        val showProgressPercentage: Boolean,
    )
    private data class ScrollPrefs(
        val autoScroll: Boolean,
        val autoScrollSpeed: Float,
        val railHeight: Int,
        val railOnLeft: Boolean,
    )
    private data class VolumePrefs(val enabled: Boolean, val inverted: Boolean, val fraction: Float)
    private data class ReaderExtraPrefs(
        val tts: TtsPrefs,
        val flags: FlagPrefs,
        val scroll: ScrollPrefs,
        val volume: VolumePrefs,
        val alwaysShowTransition: Boolean,
    )

    /** Per-novel orientation [override] + the global [default]; [resolved] is what the reader applies
     *  (the override, or the default when the override is DEFAULT/unset). */
    private data class OrientationPrefs(val override: Int, val default: Int) {
        val resolved: Int get() = if (override == ReaderOrientation.DEFAULT.flagValue) default else override
    }
}

/** A chapter sheet's row, as the merge group's [flags] answer for it. The legacy reader's sheet builds
 *  its rows here too, so the two sheets cannot say different things about one chapter. */
internal fun NovelChapter.toReaderChapterRow(
    sourceNames: Map<Long, String>,
    queued: Map<Long, NovelDownload>,
    flags: GroupChapterFlags<NovelChapter>,
) = ReaderChapterRow(
    id = id,
    title = name,
    // A novel has no scanlator, so the only subtitle is which source a merged group's chapter is from.
    subtitle = sourceNames[novelId],
    dateUpload = dateUpload,
    readProgress = (lastTextProgress / 100L).toInt().takeIf { it > 0 }?.let { "$it%" },
    read = flags.isRead(this),
    bookmark = flags.isBookmarked(this),
    downloadState = when {
        queued[id] != null -> queued.getValue(id).state.toDownloadState()
        flags.isDownloaded(this) -> Download.State.DOWNLOADED
        else -> Download.State.NOT_DOWNLOADED
    },
    // A novel chapter is one request, so there is no percentage to report while it runs.
    downloadProgress = 0,
)

/** Chapters held in the forward-prefetch cache. Small: it exists to make one step instant, not to
 *  keep a session's reading in memory. */
private const val MAX_CACHED_CHAPTERS = 5

/** A scroll reports continuously, so a write waits this long for the next one rather than hitting the
 *  database on every whole percent. */
private const val PROGRESS_SAVE_DEBOUNCE_MS = 500L

/** The shared completion rule, asked in the whole percent this reader reports in, so the threshold
 *  lives only in [ChapterProgress] and cannot drift from the manga side. */
private fun Int.completesChapter(): Boolean =
    ChapterProgress.Percent(hundredths = this * 100L).isChapterComplete
