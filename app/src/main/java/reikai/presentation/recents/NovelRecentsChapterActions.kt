package reikai.presentation.recents

import dev.zacsweers.metro.Inject
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import reikai.domain.download.rowDownloadChapters
import reikai.domain.download.runChapterAction
import reikai.domain.entry.EntryId
import reikai.domain.merge.MergeScope
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.ownersOf
import reikai.domain.source.isInstalled
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Novels' chapter verbs on recent activity, twin of [MangaRecentsChapterActions], pinned by
 * RecentsChapterActionsConformanceTest.
 */
@Inject
class NovelRecentsChapterActions(
    private val chapterRepository: NovelChapterRepository,
    private val setNovelReadStatus: SetNovelReadStatus,
    private val mergedChapterProvider: NovelMergedChapterProvider,
    // Deferred: building the manager restores the persisted queue and can start the download worker,
    // and this class is built with every recents adapter, whether or not a verb ever runs.
    private val downloadManager: () -> NovelDownloadManager,
    private val novelRepository: NovelRepository,
    private val sourceManager: NovelSourceManager,
) : RecentsChapterActions {

    // Through the read interactor, so delete-after-read fires here as on every other novel path.
    override suspend fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
        withIOContext { setNovelReadStatus.await(read, chaptersOf(chapters.groupIds(MergeScope.Group))) }
    }

    override suspend fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) {
        withIOContext { chapterRepository.setBookmarkBulk(chapters.groupIds(MergeScope.Group), bookmarked) }
    }

    override suspend fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction, scope: MergeScope) {
        withIOContext {
            val rows = chaptersOf(chapters.ownChapterIds<EntryId.Novel>())
            val fetched =
                fetchedCopies(rows, scope, { it.id }, { it.novelId }, mergedChapterProvider::stitchOf, ::chaptersOf) {
                    installedAmong(it)
                }
            downloadManager().runChapterAction(action, rowDownloadChapters(action, rows, fetched) { it.id }) {
                chaptersOf(chapters.groupIds(scope))
            }
        }
    }

    /** The chapters of [chapters] whose own novel's source is installed. */
    private suspend fun installedAmong(chapters: List<NovelChapter>): Set<Long> {
        val installed = novelRepository.ownersOf(chapters).filterValues { sourceManager.isInstalled(it.source) }.keys
        return chapters.filter { it.novelId in installed }.mapTo(HashSet()) { it.id }
    }

    override suspend fun deleteDownloads(chapters: Set<ChapterRef>, scope: MergeScope) {
        withIOContext {
            val targets = chaptersOf(chapters.groupIds(scope))
            if (targets.isNotEmpty()) downloadManager().deleteChapters(targets)
        }
    }

    private suspend fun Set<ChapterRef>.groupIds(scope: MergeScope) =
        groupChapterIds<EntryId.Novel>(scope, mergedChapterProvider::stitchOf)

    private suspend fun chaptersOf(chapterIds: List<Long>): List<NovelChapter> =
        chapterIds.mapNotNull { chapterRepository.getById(it) }
}
