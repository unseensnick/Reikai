package reikai.domain.source

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.source.model.FeedSavedSearch

/** Adding a row and restoring one stop at the same count. */
class GlobalFeedFullTest {

    private fun holding(rows: Long) = object : FeedSavedSearchRepository {
        override fun subscribeGlobal(): Flow<List<FeedSavedSearch>> = error("unused")
        override suspend fun getAll(): List<FeedSavedSearch> = error("unused")
        override suspend fun countGlobal(): Long = rows
        override suspend fun insert(sourceKey: SourceKey, savedSearchId: Long?, global: Boolean): Long =
            error("unused")
        override suspend fun updateOrders(orderedIds: List<Long>) = error("unused")
        override suspend fun delete(id: Long) = error("unused")
    }

    @Test
    fun `the feed is full at the row cap and not one row before it`() = runTest {
        listOf(
            holding(MAX_FEED_ROWS - 1L).isGlobalFeedFull(),
            holding(MAX_FEED_ROWS.toLong()).isGlobalFeedFull(),
        ) shouldBe
            listOf(false, true)
    }
}
