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

    /**
     * The first unread chapter, for a row with no recorded chapter to resume from (the recents
     * surface's newly-added lane). Twin of `GetNextChapters.await(mangaId, onlyUnread = true)`, which
     * the manga side already had; without it the lane could only resolve a target for manga.
     */
    suspend fun awaitFirstUnread(novelId: Long): NovelChapter? =
        chapterRepository.getByNovelId(novelId).firstOrNull { !it.read }
}
