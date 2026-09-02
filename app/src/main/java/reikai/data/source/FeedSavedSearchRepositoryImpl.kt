package reikai.data.source

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.source.FeedSavedSearchRepository
import reikai.domain.source.SourceKey
import reikai.domain.source.model.FeedSavedSearch
import tachiyomi.data.Database
import tachiyomi.data.Feed_saved_search
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FeedSavedSearchRepositoryImpl(
    private val database: Database,
) : FeedSavedSearchRepository {

    private val queries = database.feed_saved_searchQueries

    override fun subscribeGlobal(): Flow<List<FeedSavedSearch>> =
        queries.selectGlobal()
            .subscribeToList()
            .map { rows -> rows.mapNotNull(Feed_saved_search::toDomain) }

    override fun subscribeBySource(sourceKey: SourceKey): Flow<List<FeedSavedSearch>> =
        queries.selectBySource(sourceKey.serialize())
            .subscribeToList()
            .map { rows -> rows.mapNotNull(Feed_saved_search::toDomain) }

    override suspend fun getAll(): List<FeedSavedSearch> =
        queries.selectAll().awaitAsList().mapNotNull(Feed_saved_search::toDomain)

    override suspend fun countGlobal(): Long = queries.countGlobal().awaitAsOne()

    override suspend fun countBySource(sourceKey: SourceKey): Long =
        queries.countBySource(sourceKey.serialize()).awaitAsOne()

    override suspend fun insert(sourceKey: SourceKey, savedSearchId: Long?, global: Boolean): Long =
        database.transactionWithResult {
            queries.insert(
                sourceKey = sourceKey.serialize(),
                savedSearch = savedSearchId,
                global = global,
            )
            queries.selectLastInsertedRowId().awaitAsOne()
        }

    override suspend fun delete(id: Long) {
        queries.deleteById(id)
    }
}

/** Null when the stored source key no longer parses; see the twin in SavedSearchRepositoryImpl. */
private fun Feed_saved_search.toDomain(): FeedSavedSearch? =
    SourceKey.parse(source_key)?.let {
        FeedSavedSearch(
            id = _id,
            sourceKey = it,
            savedSearchId = saved_search,
            global = global,
            feedOrder = feed_order,
        )
    }
