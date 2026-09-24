package reikai.data.novel

import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import reikai.util.runCatchingCancellable
import tachiyomi.data.Database
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Walk a paged source's chapter pages [fromPage]..[toPage] inclusive, syncing each into the novel's
 * stored chapters (tagged with its transport index). Callers pass `fromPage = oldTotalPages` so the
 * previously-last page is re-fetched too, surfacing chapters appended to it before a new page opened.
 * Page 1 is the caller's responsibility, coming from `parseNovel` rather than `parsePage`. A page that
 * throws or returns nothing is skipped rather than fatal, so one flaky page cannot abort the rest.
 * Returns the pages' sync results added together.
 */
suspend fun walkNovelPages(
    novel: Novel,
    source: NovelSource,
    fromPage: Long,
    toPage: Long,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    database: Database,
    libraryPreferences: LibraryPreferences,
    novelDownloadManager: NovelDownloadManager? = null,
): NovelChapterSyncResult {
    var walked = NovelChapterSyncResult.UNCHANGED
    if (toPage <= 1L) return walked
    for (p in maxOf(fromPage, 1L)..toPage) {
        val key = p.toString()
        val chapters = runCatchingCancellable { source.parsePage(novel.url, key)?.chapters }.getOrNull().orEmpty()
        if (chapters.isNotEmpty()) {
            runCatchingCancellable {
                syncChaptersWithNovelSource(
                    chapters,
                    novel,
                    novelChapterRepository,
                    novelRepository,
                    database,
                    libraryPreferences,
                    page = key,
                    novelDownloadManager = novelDownloadManager,
                )
            }.onSuccess { walked += it }
        }
    }
    return walked
}
