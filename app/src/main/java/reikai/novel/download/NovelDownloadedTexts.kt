package reikai.novel.download

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import org.jsoup.Jsoup
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.ownersOf
import reikai.novel.content.NovelHtmlUtils
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat

/** [onDisk] is what a pass reads, [total] every chapter in the scope, for the share it covers. */
data class NovelDownloadedChapters(val onDisk: List<NovelChapter>, val total: Int)

/**
 * The downloaded chapters a novel-wide pass over text reads, in the reader's scope and order, and their
 * visible text. Search and word count both read through here, before the reader's find-and-replace rules.
 */
@Inject
class NovelDownloadedTexts(
    private val getNextNovelChapter: GetNextNovelChapter,
    private val novelRepository: NovelRepository,
    private val downloadCache: NovelDownloadCache,
    private val downloadManager: () -> NovelDownloadManager,
) {
    suspend fun chaptersOf(novelId: Long, sourceScoped: Boolean): NovelDownloadedChapters {
        // Only rows on disk are read, and which installed copy opens matters only to a row with none.
        val rows = getNextNovelChapter.readingRows(novelId, sourceScoped, isInstalled = { true }) { chapters, owners ->
            downloadCache.downloadedChapterIds(chapters, owners)
        }.rows
        val isHidden = getNextNovelChapter.hiddenAmong(rows)
        val shown = rows.filterNot(isHidden).sortedWith(getNextNovelChapter.readingOrder(novelId))
        val onDisk = downloadCache.downloadedChapterIds(shown, novelRepository.ownersOf(shown))
        return NovelDownloadedChapters(shown.filter { it.id in onDisk }, shown.size)
    }

    /** Hands [onChapter] each of [chapters] with its visible text, or null for a file that cannot be read. */
    suspend fun readEach(chapters: List<NovelChapter>, onChapter: suspend (NovelChapter, String?) -> Unit) {
        val owners = novelRepository.ownersOf(chapters)
        for (chapter in chapters) {
            currentCoroutineContext().ensureActive()
            val text = runCatchingCancellable {
                owners[chapter.novelId]?.let { downloadManager().getChapterText(it, chapter) }?.let { stored ->
                    Jsoup.parse(NovelHtmlUtils.normalizeContentForHtml(stored, chapter.url)).body().text()
                }
            }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not read the downloaded text of ${chapter.name}" } }
                .getOrNull()
            onChapter(chapter, text)
        }
    }
}
