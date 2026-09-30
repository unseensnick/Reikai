package reikai.domain.download

import dev.zacsweers.metro.Inject
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * What a manga chapter row's download control does, for every Reikai row: the reader's sheet and
 * Recents. [deleteTargets] are the copies the caller's view counts as downloaded. MangaViewModel keeps
 * upstream's own copy, runChapterDownloadActions, which this matches.
 */
@Inject
class MangaChapterDownloadActions(
    private val downloadManager: DownloadManager,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
) {
    suspend fun run(
        action: ChapterDownloadAction,
        chapters: List<Chapter>,
        deleteTargets: suspend () -> List<Chapter>,
    ) {
        when (action) {
            // Queueing a failed chapter again is its retry, which the downloader does for itself.
            ChapterDownloadAction.START ->
                forEachOwner(chapters) { manga, owned -> downloadManager.downloadChapters(manga, owned) }
            ChapterDownloadAction.START_NOW -> chapters.singleOrNull()?.let { downloadManager.startDownloadNow(it.id) }
            ChapterDownloadAction.CANCEL -> chapters.mapNotNull { downloadManager.getQueuedDownloadOrNull(it.id) }
                .takeIf { it.isNotEmpty() }
                ?.let(downloadManager::cancelQueuedDownloads)
            ChapterDownloadAction.DELETE -> delete(deleteTargets())
        }
    }

    /** Each chapter from its own manga's folder; an uninstalled source's stub names the same folder. */
    suspend fun delete(chapters: List<Chapter>) = forEachOwner(chapters) { manga, owned ->
        downloadManager.deleteChapters(owned, manga, sourceManager.getOrStub(manga.source))
    }

    private suspend inline fun forEachOwner(chapters: List<Chapter>, block: (Manga, List<Chapter>) -> Unit) {
        chapters.groupBy { it.mangaId }.forEach { (mangaId, owned) ->
            getManga.await(mangaId)?.let { block(it, owned) }
        }
    }
}

/**
 * Novels' twin of [MangaChapterDownloadActions.run], pinned by ChapterDownloadActionsConformanceTest.
 */
suspend fun NovelDownloadManager.runChapterAction(
    action: ChapterDownloadAction,
    chapters: List<NovelChapter>,
    deleteTargets: suspend () -> List<NovelChapter>,
) {
    when (action) {
        ChapterDownloadAction.START -> downloadChapters(chapters)
        ChapterDownloadAction.START_NOW -> chapters.singleOrNull()?.let {
            downloadChapters(listOf(it))
            startDownloadNow(it.id)
        }
        ChapterDownloadAction.CANCEL -> cancelDownloads(chapters.map { it.id })
        ChapterDownloadAction.DELETE -> deleteChapters(deleteTargets())
    }
}
