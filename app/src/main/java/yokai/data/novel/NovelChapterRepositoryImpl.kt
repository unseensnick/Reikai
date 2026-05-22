package yokai.data.novel

import co.touchlab.kermit.Logger
import yokai.data.DatabaseHandler
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.models.NovelChapter

class NovelChapterRepositoryImpl(private val handler: DatabaseHandler) : NovelChapterRepository {

    override suspend fun getByNovelId(novelId: Long): List<NovelChapter> =
        handler.awaitList { novel_chaptersQueries.getByNovelId(novelId, ::novelChapterMapper) }

    override suspend fun getById(id: Long): NovelChapter? =
        handler.awaitOneOrNull { novel_chaptersQueries.getById(id, ::novelChapterMapper) }

    override suspend fun insert(chapter: NovelChapter): Long? = try {
        handler.awaitOneOrNullExecutable(inTransaction = true) {
            novel_chaptersQueries.insert(
                novelId = chapter.novelId,
                url = chapter.url,
                name = chapter.name,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastTextProgress = chapter.lastTextProgress.toLong(),
                chapterNumber = chapter.chapterNumber.toDouble(),
                sourceOrder = chapter.sourceOrder,
                dateFetch = chapter.dateFetch,
                dateUpload = chapter.dateUpload,
            )
            novel_chaptersQueries.selectLastInsertedRowId()
        }
    } catch (e: Exception) {
        Logger.e(e) { "Failed to insert novel chapter '${chapter.url}' (novelId=${chapter.novelId})" }
        null
    }

    override suspend fun update(chapter: NovelChapter): Boolean = try {
        val chapterId = chapter.id ?: error("update() called with null id; insert first")
        handler.await {
            novel_chaptersQueries.update(
                novelId = chapter.novelId,
                url = chapter.url,
                name = chapter.name,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastTextProgress = chapter.lastTextProgress.toLong(),
                chapterNumber = chapter.chapterNumber.toDouble(),
                sourceOrder = chapter.sourceOrder,
                dateFetch = chapter.dateFetch,
                dateUpload = chapter.dateUpload,
                chapterId = chapterId,
            )
        }
        true
    } catch (e: Exception) {
        Logger.e(e) { "Failed to update novel chapter id=${chapter.id}" }
        false
    }

    override suspend fun delete(id: Long) {
        handler.await { novel_chaptersQueries.delete(id) }
    }
}
