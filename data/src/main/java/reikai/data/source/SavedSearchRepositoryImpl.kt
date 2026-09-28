package reikai.data.source

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.source.SavedSearchRepository
import reikai.domain.source.SourceKey
import reikai.domain.source.model.SavedSearch
import tachiyomi.data.Database
import tachiyomi.data.Saved_search
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SavedSearchRepositoryImpl(
    private val database: Database,
) : SavedSearchRepository {

    private val queries = database.saved_searchQueries

    override suspend fun getBySource(sourceKey: SourceKey): List<SavedSearch> =
        queries.selectBySource(sourceKey.serialize()).awaitAsList().mapNotNull(Saved_search::toDomain)

    override fun subscribeBySource(sourceKey: SourceKey): Flow<List<SavedSearch>> =
        queries.selectBySource(sourceKey.serialize())
            .subscribeToList()
            .map { rows -> rows.mapNotNull(Saved_search::toDomain) }

    override suspend fun getAll(): List<SavedSearch> =
        queries.selectAll().awaitAsList().mapNotNull(Saved_search::toDomain)

    override suspend fun insert(
        sourceKey: SourceKey,
        name: String,
        query: String?,
        filtersJson: String?,
    ): Long = database.transactionWithResult {
        queries.insert(
            sourceKey = sourceKey.serialize(),
            name = name,
            query = query,
            filtersJson = filtersJson,
        )
        queries.selectLastInsertedRowId().awaitAsOne()
    }

    override suspend fun delete(id: Long) {
        queries.deleteById(id)
    }
}

/** Null when the stored source key no longer parses, which drops that row instead of the whole list. */
private fun Saved_search.toDomain(): SavedSearch? =
    SourceKey.parse(source_key)?.let {
        SavedSearch(
            id = _id,
            sourceKey = it,
            name = name,
            query = query,
            filtersJson = filters_json,
        )
    }
