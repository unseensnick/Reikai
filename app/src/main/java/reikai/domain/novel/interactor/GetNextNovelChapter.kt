package reikai.domain.novel.interactor

import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter

/**
 * Novel twin of [tachiyomi.domain.history.interactor.GetNextChapters] for History-tab resume: given a
 * recorded chapter, return the chapter to reopen. If the recorded chapter isn't fully read, reopen it;
 * otherwise the next chapter in reading order (source_order). Null when there is nothing after it.
 */
class GetNextNovelChapter(
    private val chapterRepository: NovelChapterRepository,
) {
    suspend fun await(novelId: Long, fromChapterId: Long): NovelChapter? {
        val chapters = chapterRepository.getByNovelId(novelId) // ordered by source_order
        val index = chapters.indexOfFirst { it.id == fromChapterId }
        if (index < 0) return null
        return if (!chapters[index].read) chapters[index] else chapters.getOrNull(index + 1)
    }
}
