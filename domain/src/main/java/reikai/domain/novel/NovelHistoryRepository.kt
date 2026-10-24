package reikai.domain.novel

import kotlinx.coroutines.flow.Flow
import reikai.domain.backup.RestoredChapterHistory
import reikai.domain.novel.model.NovelHistory
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.model.NovelHistoryWithRelations
import kotlin.time.Instant

/**
 * Novel twin of [tachiyomi.domain.history.repository.HistoryRepository], backing the History tab's
 * novel feed. [getNovelHistory] is the reactive one-row-per-novel feed (search by title);
 * [getLastNovelHistory] drives tab-reselect resume. resetNovelHistory* soft-delete (last_read = NULL,
 * hidden by the feed filter so a re-read re-surfaces it); [deleteAllNovelHistory] hard-clears.
 * Pinned by HistoryFeedConformanceTest.
 */
interface NovelHistoryRepository {
    /** Empty category lists mean no constraint, matching the manga twin. */
    fun getNovelHistory(
        query: String,
        includedCategories: List<Long> = emptyList(),
        excludedCategories: List<Long> = emptyList(),
    ): Flow<List<NovelHistoryWithRelations>>
    suspend fun getLastNovelHistory(): NovelHistoryWithRelations?
    suspend fun resetNovelHistory(historyId: Long)
    suspend fun resetNovelHistoryByNovelId(novelId: Long)

    /** Answers whether the wipe went through, so a surface can say so only when it did. */
    suspend fun deleteAllNovelHistory(): Boolean
    suspend fun upsertNovelHistory(update: NovelHistoryUpdate)
    suspend fun getHistoryByNovelId(novelId: Long): List<NovelHistory>

    /** Every history row of each of [novelIds], keyed by its chapter's url, as a backup carries it. */
    suspend fun getUrlKeyedHistoryByNovelIds(novelIds: List<Long>): Map<Long, List<RestoredChapterHistory>>

    /** Backup restore of one chapter's history: the later read time and the longer duration win. */
    suspend fun restoreHistory(chapterId: Long, readAt: Instant?, readDuration: Long)

    /**
     * Backup restore of an old backup's novel-level Last read stamp: a novel with no history row gets
     * one on its latest-read chapter, and one with any history keeps it untouched.
     */
    suspend fun seedLastRead(novelId: Long, readAt: Instant)

    /** Total novel reading time (ms) across all chapters, for the Stats screen. */
    suspend fun getTotalReadDuration(): Long
}
