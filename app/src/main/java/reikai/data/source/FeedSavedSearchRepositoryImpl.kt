package reikai.data.source

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
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

    override suspend fun getAll(): List<FeedSavedSearch> =
        queries.selectAll().awaitAsList().mapNotNull(Feed_saved_search::toDomain)

    override suspend fun countGlobal(): Long = queries.countGlobal().awaitAsOne()

    /**
     * Returns the existing row's id rather than adding a second one just like it. In the transaction
     * so a restore cannot insert a duplicate it read the table before, and because two rows the
     * reader cannot tell apart would both fetch and both spend a slot of the cap.
     */
    override suspend fun insert(sourceKey: SourceKey, savedSearchId: Long?, global: Boolean): Long =
        database.transactionWithResult {
            val existing = queries.selectMatching(
                sourceKey = sourceKey.serialize(),
                global = global,
                savedSearch = savedSearchId,
            ).awaitAsOneOrNull()
            existing ?: run {
                queries.insert(
                    sourceKey = sourceKey.serialize(),
                    savedSearch = savedSearchId,
                    global = global,
                )
                queries.selectLastInsertedRowId().awaitAsOne()
            }
        }

    override suspend fun delete(id: Long) {
        queries.deleteById(id)
    }
}

/** Null when the stored source key no longer parses, the twin of the rule in SavedSearchRepositoryImpl.
 *  Both halves are pinned by their own "a row whose stored source no longer parses is left out" case,
 *  each asserting the good rows beside it survive. */
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
