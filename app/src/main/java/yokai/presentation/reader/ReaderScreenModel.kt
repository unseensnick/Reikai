package yokai.presentation.reader

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.system.launchIO
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelPreferences
import yokai.novel.download.NovelDownloadManager
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSourceManager

/**
 * Render state for the unified reader. Phase 1 serves novel content only; manga arrives in Phase 2
 * via a viewer delegate hosted in the same shell.
 */
sealed interface ReaderState {
    data object Loading : ReaderState
    data class Loaded(
        val chapterTitle: String,
        val html: String,
        /** Base URL for resolving relative links/images in the chapter HTML; null for a downloaded
         *  chapter (read from disk, no source resolved). */
        val baseUrl: String?,
        val fontSize: Int,
        val lineSpacing: Float,
        /** 0 = follow system, 1 = light, 2 = dark (mirrors [NovelPreferences.readerTheme]). */
        val theme: Int,
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

    init {
        load()
    }

    fun retry() = load()

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
                ReaderState.Loaded(
                    chapterTitle = chapter.name,
                    html = html,
                    baseUrl = baseUrl,
                    fontSize = novelPreferences.readerFontSize().get(),
                    lineSpacing = novelPreferences.readerLineSpacing().get(),
                    theme = novelPreferences.readerTheme().get(),
                )
            } catch (e: Throwable) {
                ReaderState.Failed(e.message ?: "Failed to load chapter")
            }
        }
    }
}
