package reikai.data.novel

import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import tachiyomi.data.Database

/**
 * Walk a paged source's chapter pages [fromPage]..[toPage] inclusive, syncing each into the novel's
 * stored chapters (tagged with its transport index). Callers pass `fromPage = oldTotalPages` so the
 * previously-last page is re-fetched too, surfacing chapters appended to it before a new page opened.
 * Page 1 is the caller's responsibility (it comes from `parseNovel`, not `parsePage`).
 *
 * Net-new and self-contained so S7's background update job can reuse the same walk. A page that
 * throws or returns nothing is skipped, not fatal: one flaky page shouldn't abort the rest.
 */
suspend fun walkNovelPages(
    novel: Novel,
    source: NovelSource,
    fromPage: Long,
    toPage: Long,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    database: Database,
    novelDownloadManager: NovelDownloadManager? = null,
) {
    if (toPage <= 1L) return
    for (p in maxOf(fromPage, 1L)..toPage) {
        val key = p.toString()
        val chapters = runCatching { source.parsePage(novel.url, key)?.chapters }.getOrNull().orEmpty()
        if (chapters.isNotEmpty()) {
            runCatching {
                syncChaptersWithNovelSource(
                    chapters,
                    novel,
                    novelChapterRepository,
                    novelRepository,
                    database,
                    page = key,
                    novelDownloadManager = novelDownloadManager,
                )
            }
        }
    }
}
