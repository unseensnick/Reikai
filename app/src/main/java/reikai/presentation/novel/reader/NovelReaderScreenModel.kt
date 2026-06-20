package reikai.presentation.novel.reader

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.service.TrackPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.track.TrackNovelChapter
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
    private val downloadManager: NovelDownloadManager by injectLazy()
    private val upsertNovelHistory: UpsertNovelHistory by injectLazy()

    // RK --> novel trackers (Active #8): push read progress on chapter completion
    private val trackNovelChapter: TrackNovelChapter by injectLazy()
    private val trackPreferences: TrackPreferences by injectLazy()
    // RK <--

    private var currentId: Long = initialChapterId

    /** Chapter ids in reading order, loaded once on first [load]. */
    private var orderedIds: List<Long> = emptyList()

    private val skipDupePref = novelPreferences.readerSkipDuplicateChapters()

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
    ) { display, theme ->
        NovelReaderSettings(
            fontSize = display.fontSize,
            lineHeight = display.lineHeight,
            textAlign = display.textAlign,
            padding = display.padding,
            fontFamily = display.fontFamily,
            followSystemTheme = theme.followSystem,
            backgroundColor = theme.background,
            textColor = theme.textColor,
        )
    }.stateIn(screenModelScope, SharingStarted.Eagerly, currentSettings())

    private fun currentSettings() = NovelReaderSettings(
        fontSize = novelPreferences.readerFontSize().get(),
        lineHeight = novelPreferences.readerLineSpacing().get(),
        textAlign = novelPreferences.readerTextAlign().get(),
        padding = novelPreferences.readerPadding().get(),
        fontFamily = novelPreferences.readerFontFamily().get(),
        followSystemTheme = novelPreferences.readerFollowSystemTheme().get(),
        backgroundColor = novelPreferences.readerBackgroundColor().get(),
        textColor = novelPreferences.readerTextColor().get(),
    )

    init {
        load()
    }

    fun retry() = load()

    fun next() = resolvedNext?.let { goTo(it) } ?: Unit
    fun prev() = resolvedPrev?.let { goTo(it) } ?: Unit

    private fun goTo(id: Long) {
        // Record the outgoing chapter before switching (the analog of Mihon's loadNewChapter ->
        // updateHistory + restartReadTimer), then load the new one (loadCurrent resets the timer).
        mutableState.value = NovelReaderState.Loading
        screenModelScope.launchIO {
            updateHistory()
            currentId = id
            loadCurrent()
        }
    }

    /** Stamp the current chapter into novel history and accumulate this session's read time. Called on
     *  chapter switch and on leaving the reader (the novel twin of ReaderViewModel.updateHistory). */
    suspend fun updateHistory() {
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
    }

    fun setFontSize(value: Int) = novelPreferences.readerFontSize().set(value)
    fun setLineHeight(value: Float) = novelPreferences.readerLineSpacing().set(value)
    fun setTextAlign(value: String) = novelPreferences.readerTextAlign().set(value)
    fun setPadding(value: Int) = novelPreferences.readerPadding().set(value)
    fun setFontFamily(value: String) = novelPreferences.readerFontFamily().set(value)

    fun setFollowSystemTheme() = novelPreferences.readerFollowSystemTheme().set(true)

    fun setThemePreset(preset: ReaderThemePreset) {
        novelPreferences.readerFollowSystemTheme().set(false)
        novelPreferences.readerBackgroundColor().set(preset.background)
        novelPreferences.readerTextColor().set(preset.textColor)
    }

    /** Persist the reader's scroll position. The web layer reports a whole percent (0..100); store it
     *  as 0..10000 to match [NovelChapter.lastTextProgress]. Reaching the end auto-marks read. */
    fun saveProgress(percent: Int) {
        val id = currentId
        val clamped = percent.coerceIn(0, 100)
        screenModelScope.launchIO {
            chapterRepo.setLastTextProgress(id, clamped * 100L)
            // Stamp the owning novel's last-read time so the LastRead library sort reflects this read.
            novelRepo.setLastReadAt(currentNovelId, System.currentTimeMillis())
            if (clamped >= 97) {
                chapterRepo.setReadBulk(listOf(id), true)
                val chapter = chapterRepo.getById(id)
                // RK --> push read progress to bound trackers, mirroring ReaderViewModel.updateTrackChapterRead (Active #8)
                if (trackPreferences.autoUpdateTrack.get()) {
                    chapter?.let { trackNovelChapter.await(Injekt.get<Application>(), currentNovelId, it.chapterNumber) }
                }
                // RK <--
                // The in-RAM htmlCache keeps the current view alive, so deleting the file is safe here.
                if (novelPreferences.removeAfterMarkedAsRead().get()) {
                    chapter?.let { downloadManager.deleteChapters(listOf(it)) }
                }
            }
        }
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
}
