package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.category.GetNovelCategories
import reikai.domain.download.isExcludedFromRemoval
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.reader.chapterToDeleteBehind
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadPendingDeleter

/**
 * "After reading automatically delete" in the novel reader, as Mihon's reader runs it: finishing a
 * chapter queues the one the slots retire, picked by [chapterToDeleteBehind] like manga's
 * `ReaderViewModel.deleteChapterIfNeeded`, and leaving the reader deletes the queue. So a chapter just
 * finished is still on disk to page back to. The sibling of [DeleteNovelChaptersAfterRead], which
 * fires on marking by hand.
 */
@Inject
class DeleteNovelChaptersBehindReader(
    private val novelPreferences: NovelPreferences,
    private val getNovelCategories: GetNovelCategories,
    // Deferred for the same reason as in [DeleteNovelChaptersAfterRead]: building the manager resumes
    // the persisted download queue, and a reader open must not do that.
    private val downloadManager: () -> NovelDownloadManager,
    private val chapterRepository: NovelChapterRepository,
    private val pendingDeleter: NovelDownloadPendingDeleter,
) {

    /** [orderedIds] is the session's reading order, so the slots count positions the user actually
     *  moves through rather than raw chapter numbers. */
    suspend fun await(novelId: Long, orderedIds: List<Long>, readChapterId: Long) {
        val slots = novelPreferences.removeAfterReadSlots().get()
        val targetId = orderedIds.chapterToDeleteBehind(readChapterId, slots) { it } ?: return
        val target = chapterRepository.getById(targetId) ?: return
        if (!target.read) return
        if (target.bookmark && !novelPreferences.removeBookmarkedChapters().get()) return
        val excluded = novelPreferences.removeExcludeCategories().get()
        if (isExcludedFromRemoval(excluded) { getNovelCategories.awaitByNovelId(novelId).map { it.id } }) return
        pendingDeleter.addChapters(listOf(target))
    }

    /** Deletes what [await] queued. The host calls it on leaving the reader. */
    suspend fun deletePending() {
        val ids = pendingDeleter.takePendingChapterIds()
        if (ids.isEmpty()) return
        downloadManager().deleteChapters(ids.mapNotNull { chapterRepository.getById(it) })
    }
}
