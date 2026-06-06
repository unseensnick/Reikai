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
    ) : ReaderState
    data class Failed(val message: String) : ReaderState
}

/**
 * Loads a single novel chapter's HTML for the WebView reader, independently of
 * [yokai.presentation.novel.details.NovelDetailsScreenModel] (the reader is its own pushed
 * controller). Precedence: on-disk download (offline, no source hit) -> live source. [sourceId] is
 * the chapter's owning source (a grouped sibling's, resolved by the caller), so a merged novel reads
 * from the right plugin.
 */
class ReaderScreenModel(
    private val sourceId: String,
    private val chapterId: Long,
) : StateScreenModel<ReaderState>(ReaderState.Loading) {

    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val downloadManager: NovelDownloadManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()

    /** Reactive reader display settings (font, spacing, alignment, padding, theme). [followSystemTheme]
     *  is left for [ReaderScreen] to resolve into the effective colors. */
    val settings: StateFlow<ReaderSettings> = combine(
        combine(
            novelPreferences.readerFontSize().changes(),
            novelPreferences.readerLineSpacing().changes(),
            novelPreferences.readerTextAlign().changes(),
            novelPreferences.readerPadding().changes(),
        ) { fontSize, lineHeight, textAlign, padding -> DisplayPrefs(fontSize, lineHeight, textAlign, padding) },
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
            fontFamily = "",
            followSystemTheme = theme.followSystem,
            backgroundColor = theme.background,
            textColor = theme.textColor,
        )
    }.stateIn(screenModelScope, SharingStarted.Eagerly, currentSettings())

    private data class DisplayPrefs(val fontSize: Int, val lineHeight: Float, val textAlign: String, val padding: Int)
    private data class ThemePrefs(val followSystem: Boolean, val background: String, val textColor: String)

    private fun currentSettings() = ReaderSettings(
        fontSize = novelPreferences.readerFontSize().get(),
        lineHeight = novelPreferences.readerLineSpacing().get(),
        textAlign = novelPreferences.readerTextAlign().get(),
        padding = novelPreferences.readerPadding().get(),
        fontFamily = "",
        followSystemTheme = novelPreferences.readerFollowSystemTheme().get(),
        backgroundColor = novelPreferences.readerBackgroundColor().get(),
        textColor = novelPreferences.readerTextColor().get(),
    )

    init {
        load()
    }

    fun retry() = load()

    fun setFontSize(value: Int) = novelPreferences.readerFontSize().set(value)
    fun setLineHeight(value: Float) = novelPreferences.readerLineSpacing().set(value)
    fun setTextAlign(value: String) = novelPreferences.readerTextAlign().set(value)
    fun setPadding(value: Int) = novelPreferences.readerPadding().set(value)

    fun setFollowSystemTheme() = novelPreferences.readerFollowSystemTheme().set(true)

    fun setThemePreset(preset: ReaderThemePreset) {
        novelPreferences.readerFollowSystemTheme().set(false)
        novelPreferences.readerBackgroundColor().set(preset.background)
        novelPreferences.readerTextColor().set(preset.textColor)
    }

    private fun load() {
        mutableState.value = ReaderState.Loading
        screenModelScope.launchIO {
            mutableState.value = try {
                val chapter = chapterRepo.getById(chapterId) ?: error("Chapter not found")
                val downloaded = downloadManager.getChapterText(chapter)
                val (html, baseUrl) = if (downloaded != null) {
                    downloaded to null
                } else {
                    installer.ensureLoaded()
                    val src = sourceManager.get(sourceId) ?: error("Source not installed: $sourceId")
                    src.parseChapter(chapter.url) to src.site.ifBlank { null }
                }
                ReaderState.Loaded(chapterTitle = chapter.name, html = html, baseUrl = baseUrl)
            } catch (e: Throwable) {
                ReaderState.Failed(e.message ?: "Failed to load chapter")
            }
        }
    }
}
