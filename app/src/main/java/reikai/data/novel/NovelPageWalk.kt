package reikai.data.novel

import reikai.data.novel.NovelChapterSyncRules
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import reikai.util.runCatchingCancellable
import tachiyomi.domain.library.service.LibraryPreferences

/** What [walkNovelPages] synced, and the page count the next walk starts from. */
data class NovelPageWalk(val synced: NovelChapterSyncResult, val walkedThrough: Long)

/**
 * Walk a paged source's chapter pages from the stored mark [walkedThrough] to [toPage], syncing each into the novel's
 * stored chapters (tagged with its transport index). The mark page itself is fetched again, so chapters appended to
 * it before a new page opened are picked up, and a page past it counts as opened since. [alsoPage], the page a user
 * is viewing, is synced too when the range leaves it out. Page 1 is the caller's, from `parseNovel`. A page that
 * throws is skipped so one flaky page cannot abort the rest, but the returned mark stops below it, so the next walk
 * fetches it again.
 */
suspend fun walkNovelPages(
    novel: Novel,
    source: NovelSource,
    walkedThrough: Long,
    toPage: Long,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    libraryPreferences: LibraryPreferences,
    syncRules: NovelChapterSyncRules,
    novelDownloadManager: NovelDownloadManager? = null,
    alsoPage: Long? = null,
): NovelPageWalk {
    val range = maxOf(2L, walkedThrough)..toPage
    val pages = listOfNotNull(alsoPage?.takeIf { it in 2L..toPage && it !in range }) + range
    var synced = NovelChapterSyncResult.UNCHANGED
    var mark = walkedThrough
    var failed = false
    for (p in pages) {
        val key = p.toString()
        val walked = runCatchingCancellable {
            val chapters = source.parsePage(novel.url, key)?.chapters.orEmpty()
            if (chapters.isEmpty()) {
                NovelChapterSyncResult.UNCHANGED
            } else {
                syncChaptersWithNovelSource(
                    chapters,
                    novel,
                    novelChapterRepository,
                    novelRepository,
                    libraryPreferences,
                    syncRules,
                    page = key,
                    pageOpened = p > walkedThrough,
                    novelDownloadManager = novelDownloadManager,
                )
            }
        }.getOrNull()
        if (walked != null) synced += walked
        if (p in range) {
            failed = failed || walked == null
            if (!failed) mark = maxOf(mark, p)
        }
    }
    return NovelPageWalk(synced, mark)
}
