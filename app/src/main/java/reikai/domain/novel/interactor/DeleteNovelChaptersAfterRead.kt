package reikai.domain.novel.interactor

import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager

/**
 * Delete the downloaded copies of chapters just marked read on a novel, when "delete after marked as
 * read" is on. The novel twin of how manga's [eu.kanade.domain.chapter.interactor.SetReadStatus] honors
 * `removeAfterMarkedAsRead`: novels have no central mark-read interactor, so every mark-read site (the
 * details screen, the library selection, and finishing a chapter in the reader) calls this. Honors the
 * same don't-delete-bookmarked and excluded-category guards as the reader's keep-last-N buffer.
 */
class DeleteNovelChaptersAfterRead(
    private val novelPreferences: NovelPreferences,
    private val getNovelCategories: GetNovelCategories,
    private val downloadManager: NovelDownloadManager,
    private val novelRepository: NovelRepository,
) {

    suspend fun await(novelId: Long, chapters: List<NovelChapter>) {
        if (chapters.isEmpty() || !novelPreferences.removeAfterMarkedAsRead().get()) return
        val excluded = novelPreferences.removeExcludeCategories().get().mapNotNull { it.toLongOrNull() }
        if (excluded.isNotEmpty()) {
            val cats = getNovelCategories.awaitByNovelId(novelId).map { it.id }.ifEmpty { listOf(0L) }
            if (cats.intersect(excluded.toSet()).isNotEmpty()) return
        }
        val novel = novelRepository.getById(novelId) ?: return
        val allowBookmarked = novelPreferences.removeBookmarkedChapters().get()
        val toDelete = chapters.filter {
            downloadManager.isChapterDownloaded(novel, it) && (allowBookmarked || !it.bookmark)
        }
        if (toDelete.isNotEmpty()) downloadManager.deleteChapters(toDelete)
    }
}
