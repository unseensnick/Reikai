package reikai.presentation.recents

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.entry.EntryId
import reikai.domain.manga.MergedChapterProvider
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager

/**
 * Manga's chapter verbs on recent activity, keyed by chapter id: a read-lane row has no updates row to
 * look one up by. Moved off the updates model so a surface that never builds it still acts.
 */
@Inject
class MangaRecentsChapterActions(
    private val getChapter: GetChapter,
    private val getManga: GetManga,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val downloadManager: DownloadManager,
    private val sourceManager: SourceManager,
    private val mergedChapterProvider: MergedChapterProvider,
) : RecentsChapterActions {

    override suspend fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
        withIOContext { setReadStatus.await(read, *chaptersOf(chapters.groupIds()).toTypedArray()) }
    }

    // The already-at-this-value skip reads the stored chapter, the only copy a read-lane row has.
    override suspend fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) {
        withIOContext {
            chaptersOf(chapters.groupIds())
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
    }

    override suspend fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction) {
        val chapterIds = chapters.ownChapterIds<EntryId.Manga>()
        if (chapterIds.isEmpty()) return
        withIOContext {
            when (action) {
                ChapterDownloadAction.START -> {
                    // Upstream read this off the row's own state provider; the queue is where that came from.
                    val anyFailed = chapterIds.any {
                        downloadManager.getQueuedDownloadOrNull(it)?.status == Download.State.ERROR
                    }
                    queue(chapterIds)
                    if (anyFailed) downloadManager.startDownloads()
                }
                ChapterDownloadAction.START_NOW -> chapterIds.singleOrNull()?.let(downloadManager::startDownloadNow)
                // No state patch after: the updates model drops a row's progress override once its
                // chapter leaves the queue.
                ChapterDownloadAction.CANCEL -> chapterIds.singleOrNull()
                    ?.let(downloadManager::getQueuedDownloadOrNull)
                    ?.let { downloadManager.cancelQueuedDownloads(listOf(it)) }
                ChapterDownloadAction.DELETE -> delete(chapterIds)
            }
        }
    }

    override suspend fun deleteDownloads(chapters: Set<ChapterRef>) {
        withIOContext { delete(chapters.groupIds()) }
    }

    private suspend fun Set<ChapterRef>.groupIds() = groupChapterIds<EntryId.Manga>(mergedChapterProvider::stitchOf)

    private suspend fun chaptersOf(chapterIds: List<Long>): List<Chapter> = chapterIds.mapNotNull {
        getChapter.await(it)
    }

    private suspend fun queue(chapterIds: List<Long>) {
        chaptersOf(chapterIds).groupBy { it.mangaId }.forEach { (mangaId, chapters) ->
            val manga = getManga.await(mangaId) ?: return@forEach
            // Don't download if source isn't available
            sourceManager.get(manga.source) ?: return@forEach
            downloadManager.downloadChapters(manga, chapters)
        }
    }

    private suspend fun delete(chapterIds: List<Long>) {
        chaptersOf(chapterIds).groupBy { it.mangaId }.forEach { (mangaId, chapters) ->
            val manga = getManga.await(mangaId) ?: return@forEach
            val source = sourceManager.get(manga.source) ?: return@forEach
            downloadManager.deleteChapters(chapters, manga, source)
        }
    }
}
