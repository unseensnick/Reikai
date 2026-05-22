package yokai.domain.novel

import yokai.domain.novel.models.NovelChapter

interface NovelChapterRepository {
    suspend fun getByNovelId(novelId: Long): List<NovelChapter>
    suspend fun getById(id: Long): NovelChapter?
    suspend fun insert(chapter: NovelChapter): Long?
    suspend fun update(chapter: NovelChapter): Boolean
    suspend fun delete(id: Long)
}
