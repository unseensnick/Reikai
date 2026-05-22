package yokai.domain.novel

import yokai.domain.novel.models.NovelChapter

interface NovelChapterRepository {
    suspend fun getByNovelId(novelId: Long): List<NovelChapter>
    suspend fun getById(id: Long): NovelChapter?
    suspend fun getByUrlAndNovelId(url: String, novelId: Long): NovelChapter?
    suspend fun insert(chapter: NovelChapter): Long?
    suspend fun update(chapter: NovelChapter): Boolean
    /** Focused write for the reader's auto-save path; avoids the round-trip a full update needs. */
    suspend fun setLastTextProgress(id: Long, progress: Int): Boolean
    suspend fun delete(id: Long)
}
