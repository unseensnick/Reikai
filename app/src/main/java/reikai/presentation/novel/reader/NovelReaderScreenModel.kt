package reikai.presentation.novel.reader

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
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
 * Loads novel chapters for the WebView reader. [novelId] resolves the chapter list (source order =
 * reading order) and the single source; [initialChapterId] is the entry point. Prev/next walk the
 * list within this novel/source (cross-source merged reading is S8). Chapters load live via
 * `parseChapter` (offline downloads are S5). Reading at >=97% auto-marks the chapter read.
 */
class NovelReaderScreenModel(
    private val novelId: Long,
    initialChapterId: Long,
) : StateScreenModel<NovelReaderState>(NovelReaderState.Loading) {

    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val downloadManager: NovelDownloadManager by injectLazy()

    private var currentId: Long = initialChapterId

    /** Chapter ids in reading order, loaded once on first [load]. */
    private var orderedIds: List<Long> = emptyList()

    /** Resolved once; source-dependent ops defer until set. */
    @Volatile
    private var source: NovelSource? = null

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

    fun next() = neighbor(+1)?.let { goTo(it) } ?: Unit
    fun prev() = neighbor(-1)?.let { goTo(it) } ?: Unit

    private fun goTo(id: Long) {
        currentId = id
        load()
    }

    private fun neighbor(delta: Int): Long? {
        val index = orderedIds.indexOf(currentId)
        if (index < 0) return null
        return orderedIds.getOrNull(index + delta)
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
            if (clamped >= 97) {
                chapterRepo.setReadBulk(listOf(id), true)
                // The in-RAM htmlCache keeps the current view alive, so deleting the file is safe here.
                if (novelPreferences.removeAfterMarkedAsRead().get()) {
                    chapterRepo.getById(id)?.let { downloadManager.deleteChapters(listOf(it)) }
                }
            }
        }
    }

    private fun load() {
        mutableState.value = NovelReaderState.Loading
        screenModelScope.launchIO {
            mutableState.value = try {
                if (orderedIds.isEmpty()) orderedIds = chapterRepo.getByNovelId(novelId).map { it.id }
                val id = currentId
                val chapter = chapterRepo.getById(id) ?: error("Chapter not found")
                val (html, baseUrl) = htmlCache[id] ?: loadChapterHtml(chapter).also { htmlCache[id] = it }
                val index = orderedIds.indexOf(id)
                prefetchNext(index)
                NovelReaderState.Loaded(
                    chapterTitle = chapter.name,
                    html = html,
                    baseUrl = baseUrl,
                    // Stored as 0..10000 (hundredths of a percent); the web layer wants 0..100.
                    initialProgressPercent = (chapter.lastTextProgress / 100).coerceIn(0L, 100L).toInt(),
                    hasPrev = index > 0,
                    hasNext = index in 0 until orderedIds.lastIndex,
                )
            } catch (e: Throwable) {
                NovelReaderState.Failed(e.message ?: "Failed to load chapter")
            }
        }
    }

    /** Downloaded chapter -> read the self-contained HTML from disk (no source, null base URL, images
     *  already inlined). Otherwise resolve the chapter's source and parse live, using the source site
     *  as the base URL so relative image URLs resolve. */
    private suspend fun loadChapterHtml(chapter: NovelChapter): Pair<String, String?> {
        downloadManager.getChapterText(chapter)?.let { return it to null }
        val src = resolveSource()
        return src.parseChapter(chapter.url) to src.site.ifBlank { null }
    }

    private suspend fun resolveSource(): NovelSource {
        source?.let { return it }
        try {
            installer.ensureLoaded()
        } catch (_: Throwable) {}
        val sourceId = novelRepo.getById(novelId)?.source ?: error("Novel not found")
        val resolved = sourceManager.get(sourceId) ?: error("Source not installed: $sourceId")
        source = resolved
        return resolved
    }

    /** Warm the next chapter into the cache off-thread (skipped if already cached). One speculative
     *  request per chapter open, so it stays gentle on the source. */
    private fun prefetchNext(currentIndex: Int) {
        val nextId = orderedIds.getOrNull(currentIndex + 1) ?: return
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
