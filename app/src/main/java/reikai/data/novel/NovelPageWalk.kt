package reikai.data.novel

import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.ChapterItem
import reikai.novel.source.NovelSource
import reikai.util.runCatchingCancellable
import tachiyomi.domain.library.service.LibraryPreferences

/** What [walkNovelPages] synced, and the page count the next walk starts from. */
data class NovelPageWalk(val synced: NovelChapterSyncResult, val walkedThrough: Long)

/**
 * Walk a paged source's chapter pages from the stored mark [walkedThrough] to [toPage], syncing each into the novel's
 * stored chapters (tagged with its transport index). The mark page is fetched again, so chapters appended to it are
 * picked up, and a page past it counts as opened since. [alsoPage], the page a user views, is synced too. Page 1 is
 * the caller's. A page that throws is skipped, but the returned mark stops below it, so the next walk fetches it again.
 * [dropsPagesPastLast] walks from page 2, re-tagging chapters that moved down, then drops the rows still past [toPage]
 * only when no page failed or came back empty, since such a page may be where they moved (library.md).
 *
 * @param fetched a page the caller already fetched this refresh, with its chapters, synced from those rather than twice.
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
    novelDownloadManager: NovelDownloadManager,
    alsoPage: Long? = null,
    dropsPagesPastLast: Boolean = false,
    fetched: Pair<Long, List<ChapterItem>>? = null,
): NovelPageWalk {
    val range = maxOf(2L, walkedThrough)..toPage
    val walked = if (dropsPagesPastLast) 2L..toPage else range
    val pages = listOfNotNull(alsoPage?.takeIf { it in 2L..toPage && it !in walked }) + walked
    var synced = NovelChapterSyncResult.UNCHANGED
    var mark = walkedThrough
    var failed = false
    var anyFailed = false
    for (p in pages) {
        val key = p.toString()
        val result = runCatchingCancellable {
            val chapters =
                fetched?.takeIf { it.first == p }?.second ?: source.parsePage(novel.url, key)?.chapters.orEmpty()
            if (chapters.isEmpty()) {
                // A challenge page loads without an error and lists nothing, so it counts as failed: the stored count stops
                // before it, and a dropping walk keeps the pages past it.
                null
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
                    dropPagesAbove = toPage.takeIf { dropsPagesPastLast && p == toPage && !anyFailed },
                )
            }
        }.getOrNull()
        if (result != null) synced += result
        anyFailed = anyFailed || result == null
        if (p in range) {
            failed = failed || result == null
            if (!failed) mark = maxOf(mark, p)
        }
    }
    return NovelPageWalk(synced, mark)
}
