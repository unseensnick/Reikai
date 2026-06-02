package yokai.data.novel

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.models.NovelChapter

class NovelChapterRepositoryImpl(private val handler: DatabaseHandler) : NovelChapterRepository {

    override suspend fun getByNovelId(novelId: Long): List<NovelChapter> =
        handler.awaitList { novel_chaptersQueries.getByNovelId(novelId, ::novelChapterMapper) }

    override fun getByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>> =
        handler.subscribeToList { novel_chaptersQueries.getByNovelId(novelId, ::novelChapterMapper) }

    override suspend fun getById(id: Long): NovelChapter? =
        handler.awaitOneOrNull { novel_chaptersQueries.getById(id, ::novelChapterMapper) }

    override suspend fun getByUrlAndNovelId(url: String, novelId: Long): NovelChapter? =
        handler.awaitFirstOrNull {
            novel_chaptersQueries.getByUrlAndNovelId(url, novelId, ::novelChapterMapper)
        }

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
                page = chapter.page,
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
                page = chapter.page,
                chapterId = chapterId,
            )
        }
        true
    } catch (e: Exception) {
        Logger.e(e) { "Failed to update novel chapter id=${chapter.id}" }
        false
    }

    override suspend fun setLastTextProgress(id: Long, progress: Int): Boolean = try {
        // Every other column gets null → coalesce in the SQL update keeps existing values. Only
        // last_text_progress changes per call.
        handler.await {
            novel_chaptersQueries.update(
                novelId = null,
                url = null,
                name = null,
                read = null,
                bookmark = null,
                lastTextProgress = progress.toLong(),
                chapterNumber = null,
                sourceOrder = null,
                dateFetch = null,
                dateUpload = null,
                page = null,
                chapterId = id,
            )
        }
        true
    } catch (e: Exception) {
        Logger.e(e) { "Failed to set lastTextProgress on novel chapter id=$id" }
        false
    }

    override suspend fun delete(id: Long) {
        handler.await { novel_chaptersQueries.delete(id) }
    }
}
