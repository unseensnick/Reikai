package reikai.presentation.novel.reader

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.model.readerOrientation
import reikai.domain.novel.track.TrackNovelChapter
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Render state for the WebView novel reader. Display settings live in a separate [settings] flow so
 * changing them updates the WebView live (via `reader.readerSettings.val`) without reloading.
 */
sealed interface NovelReaderState {
    data object Loading : NovelReaderState
    data class Loaded(
        val chapterTitle: String,
        val html: String,
        /** Base URL for resolving relative links/images in the chapter HTML. */
        val baseUrl: String?,
        /** Resume position as a whole percent (0..100) for the web layer's initial scroll. */
        val initialProgressPercent: Int,
        val hasPrev: Boolean,
        val hasNext: Boolean,
        /** This chapter's page on the source site (site + chapter path), for the WebView button; null
         *  for a downloaded chapter whose source isn't loaded this session. */
        val webUrl: String? = null,
        /** Whether the current chapter is bookmarked (drives the top-bar bookmark toggle). */
        val bookmarked: Boolean = false,
    ) : NovelReaderState
    data class Failed(val message: String) : NovelReaderState
}

/** Max raw-HTML entries held in the reader's session cache (LRU): current chapter, a prefetched
 *  next, and a little back-flip history. */
private const val MAX_CACHED_CHAPTERS = 5

/**
 * Loads novel chapters for the WebView reader. [orderedChapterIds] is the reading order prev/next
 * walks (the details screen's displayed list: a merged novel's unified cross-source order, or a
 * single source's order); when empty, [novelId] resolves the order from its own chapters. Each
 * chapter loads through its own source (resolved per `chapter.novelId`), so a merged session walks
 * across sources. [initialChapterId] is the entry point. Chapters load live via `parseChapter`
 * (offline downloads read from disk). Reading at >=97% auto-marks the chapter read.
 */
class NovelReaderScreenModel(
    private val novelId: Long,
    initialChapterId: Long,
    private val orderedChapterIds: LongArray = longArrayOf(),
) : StateScreenModel<NovelReaderState>(NovelReaderState.Loading) {

    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    // Shared with the manga reader so the vertical-rail geometry (height + side) is one setting for
    // both readers (Roadmap: version-181 verticalNavigator prefs).
    private val readerPreferences: ReaderPreferences by injectLazy()
    private val downloadManager: NovelDownloadManager by injectLazy()
    private val upsertNovelHistory: UpsertNovelHistory by injectLazy()
    private val setNovelViewerFlags: SetNovelViewerFlags by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelReadStatus: SetNovelReadStatus by injectLazy()

    // novel trackers (Active #8): push read progress on chapter completion
    private val trackNovelChapter: TrackNovelChapter by injectLazy()
    private val trackPreferences: TrackPreferences by injectLazy()

    private val getIncognitoState: GetIncognitoState by injectLazy()

    /** Read-aloud (TTS) controller. Owned here so it survives rotation; the WebView registers its
     *  `evaluateJavascript` sink. Auto-page-advance continues into the next chapter via [next]. */
    val ttsController = NovelTtsController(Injekt.get<Application>(), novelPreferences) { next() }

    // Captured once at reader open (mirrors ReaderViewModel). Global-only: novel sources are
    // String-keyed with no installed extension, so per-source incognito (await(sourceId)) can't apply.
    private val incognitoMode: Boolean by lazy { getIncognitoState.await(null) }

    private var currentId: Long = initialChapterId

    /** Chapter ids in reading order, loaded once on first [load]. */
    private var orderedIds: List<Long> = emptyList()

    private val skipDupePref = novelPreferences.readerSkipDuplicateChapters()

    /** Per-novel reader orientation override (a [ReaderOrientation] flagValue; 0 = follow the global
     *  default), seeded from the host novel in [init]. Keyed on the opened entry [novelId] (the anchor
     *  for a merged novel), since orientation is a book-level preference like sort/filter, not the
     *  per-source progress that [currentNovelId] tracks. */
    private val orientationOverride = MutableStateFlow(ReaderOrientation.DEFAULT.flagValue)

    /** Reading-order number of the current chapter; the skip-duplicate walk compares against it. */
    @Volatile
    private var currentNumber: Double = -1.0

    /** Owning novel of the current chapter. Defaults to the host (== owner for a standalone novel);
     *  a merged session re-points it per chapter so the last-read stamp lands on the source read. */
    @Volatile
    private var currentNovelId: Long = novelId

    /** When the current chapter began being read, for the novel-history session duration (the analog of
     *  ReaderViewModel.chapterReadStartTime). Reset whenever a chapter loads. */
    @Volatile
    private var chapterReadStartTime: Long? = null

    /** The chapters [next] / [prev] jump to, re-resolved (skip-duplicate aware) whenever the chapter or
     *  the skip-duplicate pref changes, so the buttons stay instant. */
    @Volatile
    private var resolvedPrev: Long? = null

    @Volatile
    private var resolvedNext: Long? = null

    /** Sources resolved lazily per novelId. A merged reading session walks chapters from several
     *  novels, each with its own source, so cache per novelId rather than once. */
    private val sourcesByNovel: MutableMap<Long, NovelSource> =
        java.util.Collections.synchronizedMap(HashMap())

    /** [LnPluginInstaller.ensureLoaded] needs to run once before the first source resolve. */
    @Volatile
    private var pluginsLoaded = false

    /** Session-scoped LRU of raw chapter HTML + base URL keyed by chapter id (RAM-only, dies with the
     *  screen); a prefetched next chapter opens instantly. Synchronized: the prefetch coroutine and
     *  the reader both touch it. */
    private val htmlCache: MutableMap<Long, Pair<String, String?>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, Pair<String, String?>>(MAX_CACHED_CHAPTERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<String, String?>>) =
                size > MAX_CACHED_CHAPTERS
        },
    )

    /** Reactive reader display settings; [NovelReaderScreen] resolves follow-system into colors. */
    val settings: StateFlow<NovelReaderSettings> = combine(
        combine(
            novelPreferences.readerFontSize().changes(),
            novelPreferences.readerLineSpacing().changes(),
            novelPreferences.readerTextAlign().changes(),
            novelPreferences.readerPadding().changes(),
            novelPreferences.readerFontFamily().changes(),
        ) { fontSize, lineHeight, textAlign, padding, fontFamily ->
            DisplayPrefs(fontSize, lineHeight, textAlign, padding, fontFamily)
        },
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
            ) { bionic, spacing, tapScroll, swipe -> FlagPrefs(bionic, spacing, tapScroll, swipe) },
            combine(
                novelPreferences.readerAutoScroll().changes(),
                novelPreferences.readerAutoScrollSpeed().changes(),
                readerPreferences.verticalNavigatorHeight.changes(),
                readerPreferences.verticalNavigatorOnLeft.changes(),
            ) { autoScroll, speed, railHeight, railOnLeft ->
                ScrollPrefs(autoScroll, speed, railHeight, railOnLeft)
            },
        ) { tts, flags, scroll -> ReaderExtraPrefs(tts, flags, scroll) },
    ) { display, theme, keepScreenOn, orient, extra ->
        NovelReaderSettings(
            fontSize = display.fontSize,
            lineHeight = display.lineHeight,
            textAlign = display.textAlign,
            padding = display.padding,
            fontFamily = display.fontFamily,
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
            autoScroll = extra.scroll.autoScroll,
            autoScrollSpeed = extra.scroll.autoScrollSpeed,
            railHeightPercent = extra.scroll.railHeight,
            railOnLeft = extra.scroll.railOnLeft,
        )
    }.stateIn(screenModelScope, SharingStarted.Eagerly, currentSettings())

    private fun currentSettings(): NovelReaderSettings {
        val override = orientationOverride.value
        val default = novelPreferences.readerDefaultOrientation().get()
        return NovelReaderSettings(
            fontSize = novelPreferences.readerFontSize().get(),
            lineHeight = novelPreferences.readerLineSpacing().get(),
            textAlign = novelPreferences.readerTextAlign().get(),
            padding = novelPreferences.readerPadding().get(),
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
            autoScroll = novelPreferences.readerAutoScroll().get(),
            autoScrollSpeed = novelPreferences.readerAutoScrollSpeed().get(),
            railHeightPercent = readerPreferences.verticalNavigatorHeight.get(),
            railOnLeft = readerPreferences.verticalNavigatorOnLeft.get(),
        )
    }

    /** Brightness + colour-filter overlay state, separate from [settings] so a change renders natively
     *  over the WebView and never triggers a WebView settings re-push. */
    val overlaySettings: StateFlow<NovelReaderOverlaySettings> = combine(
        novelPreferences.readerCustomBrightness().changes(),
        novelPreferences.readerCustomBrightnessValue().changes(),
        novelPreferences.readerColorFilter().changes(),
        novelPreferences.readerColorFilterValue().changes(),
        novelPreferences.readerColorFilterMode().changes(),
    ) { customBrightness, brightnessValue, colorFilter, colorFilterValue, colorFilterMode ->
        NovelReaderOverlaySettings(customBrightness, brightnessValue, colorFilter, colorFilterValue, colorFilterMode)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, currentOverlaySettings())

    private fun currentOverlaySettings() = NovelReaderOverlaySettings(
        customBrightness = novelPreferences.readerCustomBrightness().get(),
        customBrightnessValue = novelPreferences.readerCustomBrightnessValue().get(),
        colorFilter = novelPreferences.readerColorFilter().get(),
        colorFilterValue = novelPreferences.readerColorFilterValue().get(),
        colorFilterMode = novelPreferences.readerColorFilterMode().get(),
    )

    init {
        // Seed the per-novel orientation from the opened entry (the anchor for a merged novel).
        screenModelScope.launchIO {
            novelRepo.getById(novelId)?.let { orientationOverride.value = it.readerOrientation.toInt() }
        }
        load()
    }

    fun retry() = load()

    // Only a forward skip marks the departed chapter read (mirrors manga: loadNextChapter only).
    fun next() = resolvedNext?.let { goTo(it, markDepartedRead = true) } ?: Unit
    fun prev() = resolvedPrev?.let { goTo(it) } ?: Unit

    /** Jump straight to [id] from the chapters sheet (no-op if it is already the current chapter). */
    fun goToChapter(id: Long) { if (id != currentId) goTo(id) }

    fun currentChapterId(): Long = currentId

    /** Chapters in reading order, for the jump-to-chapter sheet. One query for the anchor novel covers
     *  the non-merged case; a merged novel's cross-source siblings fall back to per-id lookups. */
    suspend fun chapterList(): List<NovelChapter> {
        val anchor = chapterRepo.getByNovelId(novelId).associateBy { it.id }
        return orderedIds.mapNotNull { id -> anchor[id] ?: chapterRepo.getById(id) }
    }

    /** Toggle the current chapter's bookmark (the top-bar action). */
    fun toggleBookmark() {
        val loaded = state.value as? NovelReaderState.Loaded ?: return
        setChapterBookmark(currentId, !loaded.bookmarked)
    }

    /** Set [bookmark] on chapter [id] (the chapters sheet's swipe/toggle); reflects in the top bar when
     *  [id] is the current chapter. */
    fun setChapterBookmark(id: Long, bookmark: Boolean) {
        if (id == currentId) {
            (state.value as? NovelReaderState.Loaded)?.let { mutableState.value = it.copy(bookmarked = bookmark) }
        }
        screenModelScope.launchIO { chapterRepo.setBookmark(id, bookmark) }
    }

    /** Live download queue, for the chapters sheet's per-row download indicator. */
    val downloadQueue: StateFlow<List<NovelDownload>> get() = downloadManager.queueState

    /** Start / cancel / delete a chapter download from the chapters sheet (mirrors the details model). */
    fun onChapterDownloadAction(chapter: NovelChapter, action: ChapterDownloadAction) {
        when (action) {
            ChapterDownloadAction.START -> downloadManager.downloadChapters(listOf(chapter))
            ChapterDownloadAction.START_NOW -> {
                downloadManager.downloadChapters(listOf(chapter))
                downloadManager.startDownloadNow(chapter.id)
            }
            ChapterDownloadAction.CANCEL -> downloadManager.cancelDownloads(listOf(chapter.id))
            ChapterDownloadAction.DELETE -> downloadManager.deleteChapters(listOf(chapter))
        }
    }

    /** Per-source display names keyed by novelId, for the chapters sheet's source labels on a merged
     *  novel. Empty for a single-source novel (one distinct novelId), so no label is shown. */
    suspend fun chapterSourceNames(chapters: List<NovelChapter>): Map<Long, String> {
        val novelIds = chapters.map { it.novelId }.distinct()
        if (novelIds.size <= 1) return emptyMap()
        return novelIds.associateWith { id ->
            sourcesByNovel[id]?.name
                ?: novelRepo.getById(id)?.source?.let { sourceManager.get(it)?.name ?: it }
                ?: ""
        }
    }

    private fun goTo(id: Long, markDepartedRead: Boolean = false) {
        // Record the outgoing chapter before switching (the analog of Mihon's loadNewChapter ->
        // updateHistory + restartReadTimer), then load the new one (loadCurrent resets the timer).
        mutableState.value = NovelReaderState.Loading
        screenModelScope.launchIO {
            updateHistory()
            // mark-read-on-skip: the departed chapter + its owning novel are still current here
            // (before the reassignment + loadCurrent below re-point them to the incoming chapter).
            if (markDepartedRead) markReadOnSkip(currentId, currentNovelId)
            currentId = id
            loadCurrent()
        }
    }

    // mark-read-on-skip (opt-in): mark the chapter the user skipped away from as read (forward
    // only), the novel twin of ReaderViewModel.markChapterReadOnSkip. Reuses saveProgress's tracker push.
    private suspend fun markReadOnSkip(departedId: Long, departedNovelId: Long) {
        if (incognitoMode || !novelPreferences.readerMarkReadOnSkip().get()) return
        val chapter = chapterRepo.getById(departedId) ?: return
        if (chapter.read) return
        chapterRepo.setReadBulk(listOf(departedId), true)
        if (trackPreferences.autoUpdateTrack.get()) {
            trackNovelChapter.await(Injekt.get<Application>(), departedNovelId, chapter.chapterNumber)
        }
    }

    /** Stamp the current chapter into novel history and accumulate this session's read time. Called on
     *  chapter switch and on leaving the reader (the novel twin of ReaderViewModel.updateHistory). */
    suspend fun updateHistory() {
        if (incognitoMode) return
        val now = System.currentTimeMillis()
        val duration = chapterReadStartTime?.let { now - it } ?: 0L
        upsertNovelHistory.await(NovelHistoryUpdate(currentId, now, duration))
        chapterReadStartTime = null
    }

    /** The id [delta] steps from [currentId] in reading order. With skip-duplicate on, keeps walking
     *  past chapters whose number matches the current one (the same-number dupes a merge produces). */
    private suspend fun resolveNeighbor(delta: Int): Long? {
        var index = orderedIds.indexOf(currentId)
        if (index < 0) return null
        val skip = skipDupePref.get()
        while (true) {
            index += delta
            val id = orderedIds.getOrNull(index) ?: return null
            if (!skip || currentNumber <= 0.0) return id
            val number = chapterRepo.getById(id)?.chapterNumber
            if (number == null || number <= 0.0 || number != currentNumber) return id
        }
    }

    /** Re-resolve both neighbors (skip-duplicate aware) and warm the next chapter. */
    private suspend fun resolveBothNeighbors() {
        resolvedPrev = resolveNeighbor(-1)
        resolvedNext = resolveNeighbor(+1)
        prefetchNext()
        maybeDownloadAhead()
    }

    /** Download-ahead: enqueue the next N un-downloaded chapters in reading order (novel twin of manga's
     *  autoDownloadWhileReading). Skipped in incognito and when off. */
    private suspend fun maybeDownloadAhead() {
        if (incognitoMode) return
        val ahead = novelPreferences.autoDownloadWhileReading().get()
        if (ahead <= 0) return
        val index = orderedIds.indexOf(currentId)
        if (index < 0) return
        val nextIds = orderedIds.drop(index + 1).take(ahead)
        val toDownload = nextIds.mapNotNull { chapterRepo.getById(it) }
            .filterNot { downloadManager.isChapterDownloaded(it) }
        if (toDownload.isNotEmpty()) downloadManager.downloadChapters(toDownload)
    }

    fun setFontSize(value: Int) = novelPreferences.readerFontSize().set(value)
    fun setLineHeight(value: Float) = novelPreferences.readerLineSpacing().set(value)
    fun setTextAlign(value: String) = novelPreferences.readerTextAlign().set(value)
    fun setPadding(value: Int) = novelPreferences.readerPadding().set(value)
    fun setFontFamily(value: String) = novelPreferences.readerFontFamily().set(value)

    fun setKeepScreenOn(value: Boolean) = novelPreferences.readerKeepScreenOn().set(value)

    // Text-to-speech setters. Rate/pitch/voice are pushed to the live audio engine too (the WebView's
    // tts block only steers core.js); an engine swap rebuilds the backend.
    fun setTtsEnabled(value: Boolean) {
        novelPreferences.readerTtsEnabled().set(value)
        if (!value) ttsController.stop()
    }
    fun setTtsRate(value: Float) {
        novelPreferences.readerTtsRate().set(value)
        ttsController.refreshSettings(engineChanged = false)
    }
    fun setTtsPitch(value: Float) {
        novelPreferences.readerTtsPitch().set(value)
        ttsController.refreshSettings(engineChanged = false)
    }
    fun setTtsAutoPageAdvance(value: Boolean) = novelPreferences.readerTtsAutoPageAdvance().set(value)
    fun setTtsScrollToTop(value: Boolean) = novelPreferences.readerTtsScrollToTop().set(value)
    fun setTtsEngine(packageName: String) {
        novelPreferences.readerTtsEngine().set(packageName)
        novelPreferences.readerTtsVoice().set("")
        ttsController.refreshSettings(engineChanged = true)
    }
    fun setTtsVoice(name: String) {
        novelPreferences.readerTtsVoice().set(name)
        ttsController.refreshSettings(engineChanged = false)
    }
    fun setTtsLanguages(languages: Set<String>) = novelPreferences.readerTtsLanguages().set(languages)

    fun setBionicReading(value: Boolean) = novelPreferences.readerBionicReading().set(value)
    fun setRemoveExtraSpacing(value: Boolean) = novelPreferences.readerRemoveExtraSpacing().set(value)
    fun setTapToScroll(value: Boolean) = novelPreferences.readerTapToScroll().set(value)
    fun setSwipeGestures(value: Boolean) = novelPreferences.readerSwipeGestures().set(value)
    fun setAutoScroll(value: Boolean) = novelPreferences.readerAutoScroll().set(value)
    fun setAutoScrollSpeed(value: Float) = novelPreferences.readerAutoScrollSpeed().set(value)
    fun setTtsButtonPosition(x: Int, y: Int) {
        novelPreferences.readerTtsButtonX().set(x)
        novelPreferences.readerTtsButtonY().set(y)
    }
    fun ttsButtonPosition(): Pair<Int, Int> =
        novelPreferences.readerTtsButtonX().get() to novelPreferences.readerTtsButtonY().get()
    fun ttsEnginePackage(): String = novelPreferences.readerTtsEngine().get()
    fun ttsVoiceName(): String = novelPreferences.readerTtsVoice().get()
    fun ttsLanguages(): Set<String> = novelPreferences.readerTtsLanguages().get()

    /** Set this novel's reader orientation (a [ReaderOrientation] flagValue; DEFAULT = follow the
     *  global default). Writes only the orientation bits of the anchor's viewer_flags via
     *  [SetNovelViewerFlags]; the override flow updates the live settings + the apply effect immediately. */
    fun setOrientation(flagValue: Int) {
        orientationOverride.value = flagValue
        screenModelScope.launchIO {
            setNovelViewerFlags.awaitSetOrientation(novelId, flagValue.toLong())
        }
    }

    fun setFollowSystemTheme() = novelPreferences.readerFollowSystemTheme().set(true)

    fun setThemePreset(preset: ReaderThemePreset) {
        novelPreferences.readerFollowSystemTheme().set(false)
        novelPreferences.readerBackgroundColor().set(preset.background)
        novelPreferences.readerTextColor().set(preset.textColor)
    }

    fun setCustomBrightness(enabled: Boolean) = novelPreferences.readerCustomBrightness().set(enabled)
    fun setCustomBrightnessValue(value: Int) = novelPreferences.readerCustomBrightnessValue().set(value)
    fun setColorFilter(enabled: Boolean) = novelPreferences.readerColorFilter().set(enabled)
    fun setColorFilterValue(value: Int) = novelPreferences.readerColorFilterValue().set(value)
    fun setColorFilterMode(mode: Int) = novelPreferences.readerColorFilterMode().set(mode)

    /** Persist the reader's scroll position. The web layer reports a whole percent (0..100); store it
     *  as 0..10000 to match [NovelChapter.lastTextProgress]. Reaching the end auto-marks read. */
    fun saveProgress(percent: Int) {
        if (incognitoMode) return
        val id = currentId
        val clamped = percent.coerceIn(0, 100)
        screenModelScope.launchIO {
            chapterRepo.setLastTextProgress(id, clamped * 100L)
            // Stamp the owning novel's last-read time so the LastRead library sort reflects this read.
            novelRepo.setLastReadAt(currentNovelId, System.currentTimeMillis())
            if (clamped >= 97) {
                // Fetch before marking so the shared interactor sees the chapter as still unread; it flips
                // read + honors "delete after marked as read" (the in-RAM htmlCache keeps this view alive).
                val chapter = chapterRepo.getById(id)
                setNovelReadStatus.await(true, listOfNotNull(chapter))
                // push read progress to bound trackers, mirroring ReaderViewModel.updateTrackChapterRead (Active #8)
                if (trackPreferences.autoUpdateTrack.get()) {
                    chapter?.let { trackNovelChapter.await(Injekt.get<Application>(), currentNovelId, it.chapterNumber) }
                }
                maybeDeleteAfterRead(id)
            }
        }
    }

    /** Keep the last N read chapters downloaded (the [NovelPreferences.removeAfterReadSlots] buffer):
     *  delete the chapter [slots] positions back in reading order, so sequential reading keeps a rolling
     *  buffer. Skips a bookmarked chapter unless allowed and novels in an excluded category. The separate
     *  "delete after marked as read" pref is handled by [deleteNovelChaptersAfterRead] on the mark itself. */
    private suspend fun maybeDeleteAfterRead(readChapterId: Long) {
        val slots = novelPreferences.removeAfterReadSlots().get()
        if (slots < 0) return
        val index = orderedIds.indexOf(readChapterId)
        if (index < 0) return
        val targetId = orderedIds.getOrNull(index - slots) ?: return
        val target = chapterRepo.getById(targetId) ?: return
        if (!target.read) return
        if (target.bookmark && !novelPreferences.removeBookmarkedChapters().get()) return
        val excluded = novelPreferences.removeExcludeCategories().get().mapNotNull { it.toLongOrNull() }
        if (excluded.isNotEmpty()) {
            val cats = getNovelCategories.awaitByNovelId(currentNovelId).map { it.id }.ifEmpty { listOf(0L) }
            if (cats.intersect(excluded.toSet()).isNotEmpty()) return
        }
        downloadManager.deleteChapters(listOf(target))
    }

    private fun load() {
        mutableState.value = NovelReaderState.Loading
        screenModelScope.launchIO { loadCurrent() }
    }

    private suspend fun loadCurrent() {
        mutableState.value = try {
            if (orderedIds.isEmpty()) {
                orderedIds = if (orderedChapterIds.isNotEmpty()) {
                    orderedChapterIds.toList()
                } else {
                    chapterRepo.getByNovelId(novelId).map { it.id }
                }
            }
            val id = currentId
            val chapter = chapterRepo.getById(id) ?: error("Chapter not found")
            currentNumber = chapter.chapterNumber
            currentNovelId = chapter.novelId
            chapterReadStartTime = System.currentTimeMillis()
            ttsController.setNowPlaying(chapter.name)
            val (html, baseUrl) = htmlCache[id] ?: loadChapterHtml(chapter).also { htmlCache[id] = it }
            resolveBothNeighbors()
            NovelReaderState.Loaded(
                chapterTitle = chapter.name,
                html = html,
                baseUrl = baseUrl,
                // Stored as 0..10000 (hundredths of a percent); the web layer wants 0..100.
                initialProgressPercent = (chapter.lastTextProgress / 100).coerceIn(0L, 100L).toInt(),
                hasPrev = resolvedPrev != null,
                hasNext = resolvedNext != null,
                // Only from an already-resolved source (so offline downloaded reading stays instant).
                webUrl = sourcesByNovel[chapter.novelId]?.webUrl(chapter.url),
                bookmarked = chapter.bookmark,
            )
        } catch (e: Throwable) {
            NovelReaderState.Failed(e.message ?: "Failed to load chapter")
        }
    }

    /** Downloaded chapter -> read the self-contained HTML from disk (no source, null base URL, images
     *  already inlined). Otherwise resolve the chapter's source and parse live, using the source site
     *  as the base URL so relative image URLs resolve. */
    private suspend fun loadChapterHtml(chapter: NovelChapter): Pair<String, String?> {
        downloadManager.getChapterText(chapter)?.let { return it to null }
        val src = resolveSourceFor(chapter.novelId)
        return src.parseChapter(chapter.url) to src.site.ifBlank { null }
    }

    /** Resolve (and cache) the source owning [forNovelId]. Each chapter in a merged session resolves
     *  by its own `novelId`, so prev/next can cross source boundaries. */
    private suspend fun resolveSourceFor(forNovelId: Long): NovelSource {
        sourcesByNovel[forNovelId]?.let { return it }
        if (!pluginsLoaded) {
            runCatching { installer.ensureLoaded() }.onSuccess { pluginsLoaded = true }
        }
        val sourceId = novelRepo.getById(forNovelId)?.source ?: error("Novel not found")
        val resolved = sourceManager.get(sourceId) ?: error("Source not installed: $sourceId")
        sourcesByNovel[forNovelId] = resolved
        return resolved
    }

    /** Warm the resolved next chapter into the cache off-thread (skipped if already cached). One
     *  speculative request per chapter open, so it stays gentle on the source. */
    private fun prefetchNext() {
        val nextId = resolvedNext ?: return
        if (htmlCache.containsKey(nextId)) return
        screenModelScope.launchIO {
            runCatching {
                val next = chapterRepo.getById(nextId) ?: return@launchIO
                htmlCache[nextId] = loadChapterHtml(next)
            }
        }
    }

    private data class DisplayPrefs(
        val fontSize: Int,
        val lineHeight: Float,
        val textAlign: String,
        val padding: Int,
        val fontFamily: String,
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
    )
    private data class ScrollPrefs(
        val autoScroll: Boolean,
        val autoScrollSpeed: Float,
        val railHeight: Int,
        val railOnLeft: Boolean,
    )
    private data class ReaderExtraPrefs(val tts: TtsPrefs, val flags: FlagPrefs, val scroll: ScrollPrefs)

    override fun onDispose() {
        super.onDispose()
        ttsController.shutdown()
    }

    /** Per-novel orientation [override] + the global [default]; [resolved] is what the reader applies
     *  (the override, or the default when the override is DEFAULT/unset). */
    private data class OrientationPrefs(val override: Int, val default: Int) {
        val resolved: Int get() = if (override == ReaderOrientation.DEFAULT.flagValue) default else override
    }
}
