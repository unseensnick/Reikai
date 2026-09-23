package reikai.novel.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import reikai.data.novel.mergeRefreshedNovel
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.data.novel.toNovel
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.novel.download.NovelChapterSaver
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.coroutines.cancellation.CancellationException

/**
 * Applies a page the user loaded in the in-app browser to a stored novel, through its source's
 * [NovelPageFetch]: details and chapters as a refresh stores them, a chapter's text as a download does.
 */
@Inject
@SingleIn(AppScope::class)
class NovelPageFetcher(
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    private val sourceManager: NovelSourceManager,
    private val database: Database,
    private val libraryPreferences: LibraryPreferences,
    private val saver: NovelChapterSaver,
) {

    /** What a page can be used for on [novelId]'s source; empty when it takes no page. */
    suspend fun kinds(novelId: Long): Set<NovelPageKind> =
        novelRepo.getById(novelId)?.let { sourceManager.get(it.source) }?.pageFetch?.kinds.orEmpty()

    suspend fun useForDetails(novelId: Long, url: String, html: String): Boolean = attempt {
        val novel = novelRepo.getById(novelId) ?: return@attempt false
        val source = sourceManager.get(novel.source) ?: return@attempt false
        val fetch = source.pageFetch ?: return@attempt false
        val parsed = fetch.details(novel.url, url, html).toNovel(sourceId = source.id, favorite = novel.favorite)
        val merged = mergeRefreshedNovel(novel, parsed)
        if (merged != novel) novelRepo.update(merged)
        true
    } ?: false

    /** How many chapters the page listed, 0 when it listed none, or null when it could not be read. */
    suspend fun useForChapters(novelId: Long, url: String, html: String): Int? = attempt {
        val novel = novelRepo.getById(novelId) ?: return@attempt null
        val fetch = sourceManager.get(novel.source)?.pageFetch ?: return@attempt null
        val chapters = fetch.chapters(novel.url, url, html)
        if (chapters.isEmpty()) return@attempt 0
        syncChaptersWithNovelSource(chapters, novel, chapterRepo, novelRepo, database, libraryPreferences)
        chapters.size
    }

    suspend fun useForChapterText(chapterId: Long, url: String, html: String): Boolean = attempt {
        val chapter = chapterRepo.getById(chapterId) ?: return@attempt false
        val novel = novelRepo.getById(chapter.novelId) ?: return@attempt false
        val source = sourceManager.get(novel.source) ?: return@attempt false
        val fetch = source.pageFetch ?: return@attempt false
        saver.save(novel, chapter, source, fetch.chapterText(chapter.url, url, html))
    } ?: false

    // A source's parser runs on a page it did not request, so a failure is reported, never thrown.
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        withIOContext { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Could not use the page" }
        null
    }
}
