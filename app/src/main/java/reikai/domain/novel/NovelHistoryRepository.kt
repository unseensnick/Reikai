package reikai.domain.novel

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.model.NovelHistoryWithRelations

/**
 * Novel twin of [tachiyomi.domain.history.repository.HistoryRepository], backing the History tab's
 * novel feed. [getNovelHistory] is the reactive one-row-per-novel feed (search by title);
 * [getLastNovelHistory] drives tab-reselect resume. resetNovelHistory* soft-delete (last_read = 0,
 * hidden by the feed filter so a re-read re-surfaces it); [deleteAllNovelHistory] hard-clears.
 */
interface NovelHistoryRepository {
    fun getNovelHistory(query: String): Flow<List<NovelHistoryWithRelations>>
    suspend fun getLastNovelHistory(): NovelHistoryWithRelations?
    suspend fun resetNovelHistory(historyId: Long)
    suspend fun resetNovelHistoryByNovelId(novelId: Long)
    suspend fun deleteAllNovelHistory()
    suspend fun upsertNovelHistory(update: NovelHistoryUpdate)
}
