package reikai.domain.source

import kotlinx.coroutines.flow.Flow
import reikai.domain.source.model.FeedSavedSearch

/**
 * Storage for feed rows, of both content types and both scopes: the Browse feed (global) and each
 * source's own. Unreadable source keys are skipped for the reason [SavedSearchRepository] gives.
 */
interface FeedSavedSearchRepository {

    /** The Browse feed's rows, in feed order. */
    fun subscribeGlobal(): Flow<List<FeedSavedSearch>>

    /** One source's own feed rows, in feed order. */
    fun subscribeBySource(sourceKey: SourceKey): Flow<List<FeedSavedSearch>>

    /** Every row of both scopes, for backup. */
    suspend fun getAll(): List<FeedSavedSearch>

    /** Row counts, which is what the cap on how many rows a feed may hold is checked against. */
    suspend fun countGlobal(): Long

    suspend fun countBySource(sourceKey: SourceKey): Long

    /**
     * Inserts and returns the new row id. [savedSearchId] null means the row shows the source's own
     * listing rather than a saved search.
     */
    suspend fun insert(sourceKey: SourceKey, savedSearchId: Long?, global: Boolean): Long

    suspend fun delete(id: Long)
}
