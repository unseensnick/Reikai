package reikai.presentation.recents

import dev.zacsweers.metro.Inject
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import tachiyomi.core.common.util.lang.withIOContext

/** Novels' chapter verbs on recent activity, the twin of [MangaRecentsChapterActions]. */
@Inject
class NovelRecentsChapterActions(
    private val chapterRepository: NovelChapterRepository,
    private val setNovelReadStatus: SetNovelReadStatus,
    private val mergedChapterProvider: NovelMergedChapterProvider,
    // Deferred: building the manager restores the persisted queue and can start the download worker,
    // and this class is built with every recents adapter, whether or not a verb ever runs.
    private val downloadManager: () -> NovelDownloadManager,
) : RecentsChapterActions {

    // Through the read interactor, so delete-after-read fires here as on every other novel path.
    override suspend fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
        withIOContext { setNovelReadStatus.await(read, chaptersOf(chapters.groupIds())) }
    }

    override suspend fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) {
        withIOContext { chapterRepository.setBookmarkBulk(chapters.groupIds(), bookmarked) }
    }

    // Per chapter, mirroring the novel details download-action mapping.
    override suspend fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction) {
        withIOContext {
            chaptersOf(chapters.ownChapterIds<EntryId.Novel>()).forEach { chapter ->
                when (action) {
                    ChapterDownloadAction.START -> downloadManager().downloadChapters(listOf(chapter))
                    ChapterDownloadAction.START_NOW -> {
                        downloadManager().downloadChapters(listOf(chapter))
                        downloadManager().startDownloadNow(chapter.id)
                    }
                    ChapterDownloadAction.CANCEL -> downloadManager().cancelDownloads(listOf(chapter.id))
                    ChapterDownloadAction.DELETE -> downloadManager().deleteChapters(listOf(chapter))
                }
            }
        }
    }

    override suspend fun deleteDownloads(chapters: Set<ChapterRef>) {
        withIOContext {
            val targets = chaptersOf(chapters.groupIds())
            if (targets.isNotEmpty()) downloadManager().deleteChapters(targets)
        }
    }

    private suspend fun Set<ChapterRef>.groupIds() = groupChapterIds<EntryId.Novel>(mergedChapterProvider::stitchOf)

    private suspend fun chaptersOf(chapterIds: List<Long>): List<NovelChapter> =
        chapterIds.mapNotNull { chapterRepository.getById(it) }
}
