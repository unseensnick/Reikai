package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.model.NovelHistoryWithRelations
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class NovelHistoryRepositoryImpl(
    private val database: Database,
) : NovelHistoryRepository {

    override fun getNovelHistory(query: String): Flow<List<NovelHistoryWithRelations>> =
        database.novelHistoryViewQueries.novelHistory(query, ::mapNovelHistoryWithRelations).subscribeToList()

    override suspend fun getLastNovelHistory(): NovelHistoryWithRelations? =
        database.novelHistoryViewQueries.getLatestNovelHistory(::mapNovelHistoryWithRelations).awaitAsOneOrNull()

    override suspend fun resetNovelHistory(historyId: Long) {
        try {
            database.novel_historyQueries.resetById(historyId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to reset novel history id=$historyId" }
        }
    }

    override suspend fun resetNovelHistoryByNovelId(novelId: Long) {
        try {
            database.novel_historyQueries.resetByNovelId(novelId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to reset novel history for novelId=$novelId" }
        }
    }

    override suspend fun deleteAllNovelHistory() {
        try {
            database.novel_historyQueries.removeAll()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to clear novel history" }
        }
    }

    override suspend fun upsertNovelHistory(update: NovelHistoryUpdate) {
        try {
            database.novel_historyQueries.upsert(update.chapterId, update.readAt, update.sessionReadDuration)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to upsert novel history chapterId=${update.chapterId}" }
        }
    }

    override suspend fun getTotalReadDuration(): Long =
        database.novel_historyQueries.getTotalReadDuration().awaitAsOne()
}
