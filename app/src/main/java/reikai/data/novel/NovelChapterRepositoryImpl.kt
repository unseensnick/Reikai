package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class NovelChapterRepositoryImpl(
    private val database: Database,
) : NovelChapterRepository {

    override suspend fun getByNovelId(novelId: Long): List<NovelChapter> =
        database.novel_chaptersQueries.getByNovelId(novelId, ::mapNovelChapter).awaitAsList()

    override fun getByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>> =
        database.novel_chaptersQueries.getByNovelId(novelId, ::mapNovelChapter).subscribeToList()

    override suspend fun getByNovelIdAndPage(novelId: Long, page: String): List<NovelChapter> =
        database.novel_chaptersQueries.getByNovelIdAndPage(novelId, page, ::mapNovelChapter).awaitAsList()

    override fun getByNovelIdAndPageAsFlow(novelId: Long, page: String): Flow<List<NovelChapter>> =
        database.novel_chaptersQueries.getByNovelIdAndPage(novelId, page, ::mapNovelChapter).subscribeToList()

    override suspend fun getDistinctPages(novelId: Long): List<String> =
        database.novel_chaptersQueries.getDistinctPages(novelId).awaitAsList()

    override suspend fun getById(id: Long): NovelChapter? =
        database.novel_chaptersQueries.getById(id, ::mapNovelChapter).awaitAsOneOrNull()

    override suspend fun getByUrlAndNovelId(url: String, novelId: Long): NovelChapter? =
        database.novel_chaptersQueries.getByUrlAndNovelId(url, novelId, ::mapNovelChapter).awaitAsOneOrNull()

    override suspend fun insert(chapter: NovelChapter): Long? = try {
        database.transactionWithResult {
            database.novel_chaptersQueries.insert(
                novelId = chapter.novelId,
                url = chapter.url,
                name = chapter.name,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastTextProgress = chapter.lastTextProgress,
                chapterNumber = chapter.chapterNumber,
                sourceOrder = chapter.sourceOrder,
                dateFetch = chapter.dateFetch,
                dateUpload = chapter.dateUpload,
                page = chapter.page,
            )
            database.novel_chaptersQueries.selectLastInsertedRowId().awaitAsOne()
        }
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to insert novel chapter '${chapter.url}' (novelId=${chapter.novelId})" }
        null
    }

    override suspend fun update(chapter: NovelChapter): Boolean = try {
        database.novel_chaptersQueries.update(
            novelId = chapter.novelId,
            url = chapter.url,
            name = chapter.name,
            read = chapter.read,
            bookmark = chapter.bookmark,
            lastTextProgress = chapter.lastTextProgress,
            chapterNumber = chapter.chapterNumber,
            sourceOrder = chapter.sourceOrder,
            dateFetch = chapter.dateFetch,
            dateUpload = chapter.dateUpload,
            page = chapter.page,
            chapterId = chapter.id,
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to update novel chapter id=${chapter.id}" }
        false
    }

    override suspend fun setLastTextProgress(id: Long, progress: Long): Boolean =
        // Null every column but last_text_progress so coalesce keeps the rest.
        updateSingleColumn(id, lastTextProgress = progress)

    override suspend fun setRead(id: Long, read: Boolean): Boolean =
        // Null everywhere but read so coalesce keeps the rest (notably source_order, which a
        // merged unified-list copy would otherwise overwrite with its synthetic value).
        updateSingleColumn(id, read = read)

    override suspend fun setBookmark(id: Long, bookmark: Boolean): Boolean =
        updateSingleColumn(id, bookmark = bookmark)

    override suspend fun setReadBulk(ids: List<Long>, read: Boolean): Boolean = try {
        // One transaction for the whole batch. Marking unread also rewinds text progress.
        database.transaction {
            ids.forEach { id ->
                database.novel_chaptersQueries.update(
                    novelId = null, url = null, name = null, read = read, bookmark = null,
                    lastTextProgress = if (!read) 0L else null,
                    chapterNumber = null, sourceOrder = null,
                    dateFetch = null, dateUpload = null, page = null, chapterId = id,
                )
            }
        }
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to bulk set read on ${ids.size} novel chapters" }
        false
    }

    override suspend fun setDownloaded(id: Long, downloaded: Boolean): Boolean = try {
        database.novel_chaptersQueries.setDownloaded(downloaded, id)
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to set downloaded on novel chapter id=$id" }
        false
    }

    override suspend fun delete(id: Long) {
        database.novel_chaptersQueries.delete(id)
    }

    private suspend fun updateSingleColumn(
        id: Long,
        read: Boolean? = null,
        bookmark: Boolean? = null,
        lastTextProgress: Long? = null,
    ): Boolean = try {
        database.novel_chaptersQueries.update(
            novelId = null, url = null, name = null, read = read, bookmark = bookmark,
            lastTextProgress = lastTextProgress, chapterNumber = null, sourceOrder = null,
            dateFetch = null, dateUpload = null, page = null, chapterId = id,
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to update novel chapter id=$id" }
        false
    }
}
