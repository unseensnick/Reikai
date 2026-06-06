package yokai.presentation.reader

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.models.NovelChapter
import yokai.novel.download.NovelDownloadManager
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSourceManager

/**
 * Render state for the unified reader. Phase 1 serves novel content only; manga arrives in Phase 2
 * via a viewer delegate hosted in the same shell. Display settings live in a separate [settings] flow
 * so changing them updates the WebView live (via `reader.readerSettings.val`) without reloading.
 */
sealed interface ReaderState {
    data object Loading : ReaderState
    data class Loaded(
        val chapterTitle: String,
        val html: String,
        /** Base URL for resolving relative links/images in the chapter HTML; null for a downloaded
         *  chapter (read from disk, no source resolved). */
        val baseUrl: String?,
        /** Resume position as a whole percent (0..100) for the web layer's initial scroll. */
        val initialProgressPercent: Int,
        val hasPrev: Boolean,
        val hasNext: Boolean,
    ) : ReaderState
    data class Failed(val message: String) : ReaderState
}

/** Max raw-HTML entries held in the reader's session cache (LRU): current chapter, a prefetched
 *  next, and a little back-flip history. */
private const val MAX_CACHED_CHAPTERS = 5

/**
 * Loads novel chapters for the WebView reader, independently of
 * [yokai.presentation.novel.details.NovelDetailsScreenModel] (the reader is its own pushed
 * controller). [chapterIds] is the displayed reading order (a merged novel mixes sources); the reader
 * navigates within it and resolves each chapter's own source via [NovelRepository], so prev/next can
 * cross sources. Per chapter: on-disk download (offline, no source hit) -> live source.
 */
class ReaderScreenModel(
    private val chapterIds: List<Long>,
    initialChapterId: Long,
) : StateScreenModel<ReaderState>(ReaderState.Loading) {

    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val downloadManager: NovelDownloadManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()

    private var currentId: Long = initialChapterId

    /** Session-scoped LRU of raw chapter HTML + base URL keyed by chapter id (RAM-only, dies with the
     *  screen); a prefetched next chapter opens instantly. Synchronized: the prefetch coroutine and
     *  the reader both touch it. */
    private val htmlCache: MutableMap<Long, Pair<String, String?>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, Pair<String, String?>>(MAX_CACHED_CHAPTERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<String, String?>>) =
                size > MAX_CACHED_CHAPTERS
        },
    )

    /** Reactive reader display settings (font, spacing, alignment, padding, theme). [followSystemTheme]
     *  is left for [ReaderScreen] to resolve into the effective colors. */
    val settings: StateFlow<ReaderSettings> = combine(
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
        ReaderSettings(
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

    private fun currentSettings() = ReaderSettings(
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
        val index = chapterIds.indexOf(currentId)
        if (index < 0) return null
        return chapterIds.getOrNull(index + delta)
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
     *  as 0..10000 to match [NovelChapter.lastTextProgress]. */
    fun saveProgress(percent: Int) {
        val id = currentId
        screenModelScope.launchIO {
            chapterRepo.setLastTextProgress(id, percent.coerceIn(0, 100) * 100)
        }
    }

    private fun load() {
        mutableState.value = ReaderState.Loading
        screenModelScope.launchIO {
            val id = currentId
            mutableState.value = try {
                val chapter = chapterRepo.getById(id) ?: error("Chapter not found")
                val (html, baseUrl) = htmlCache[id] ?: loadChapterHtml(chapter).also { htmlCache[id] = it }
                val index = chapterIds.indexOf(id)
                prefetchNext(index)
                ReaderState.Loaded(
                    chapterTitle = chapter.name,
                    html = html,
                    baseUrl = baseUrl,
                    // Stored as 0..10000 (hundredths of a percent); the web layer wants 0..100.
                    initialProgressPercent = (chapter.lastTextProgress / 100).coerceIn(0, 100),
                    hasPrev = index > 0,
                    hasNext = index in 0 until chapterIds.lastIndex,
                )
            } catch (e: Throwable) {
                ReaderState.Failed(e.message ?: "Failed to load chapter")
            }
        }
    }

    /** Downloaded chapter -> read from disk (no source, null base URL); otherwise resolve the
     *  chapter's own source and parse live, returning the raw HTML and the source site as base URL. */
    private suspend fun loadChapterHtml(chapter: NovelChapter): Pair<String, String?> {
        val downloaded = downloadManager.getChapterText(chapter)
        if (downloaded != null) return downloaded to null
        installer.ensureLoaded()
        val sourceId = novelRepo.getById(chapter.novelId)?.source ?: error("Novel not found")
        val src = sourceManager.get(sourceId) ?: error("Source not installed: $sourceId")
        return src.parseChapter(chapter.url) to src.site.ifBlank { null }
    }

    /** Warm the next chapter into the cache off-thread (skipped if already cached). One speculative
     *  request per chapter open, so it stays gentle on the source. */
    private fun prefetchNext(currentIndex: Int) {
        val nextId = chapterIds.getOrNull(currentIndex + 1) ?: return
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
