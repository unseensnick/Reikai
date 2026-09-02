package reikai.domain.source

import kotlinx.coroutines.flow.Flow
import reikai.domain.source.model.FeedSavedSearch

/**
 * How many rows the Browse feed may hold. Komikku's number, kept so a feed stays a glance. Here rather
 * than on the screen because a restore has to respect it too, and a backup is untrusted input.
 */
const val MAX_FEED_ROWS = 20

/**
 * Storage for feed rows, of both content types. Rows carry a `global` flag, but only the Browse feed
 * is read today, so everything below reads that scope. Unreadable source keys are skipped for the
 * reason [SavedSearchRepository] gives.
 */
interface FeedSavedSearchRepository {

    /** The Browse feed's rows, in feed order. */
    fun subscribeGlobal(): Flow<List<FeedSavedSearch>>

    /** Every row of both scopes, for backup. */
    suspend fun getAll(): List<FeedSavedSearch>

    /** Row counts, which is what the cap on how many rows a feed may hold is checked against. */
    suspend fun countGlobal(): Long

    /**
     * Inserts and returns the new row id. [savedSearchId] null means the row shows the source's own
     * listing rather than a saved search.
     */
    suspend fun insert(sourceKey: SourceKey, savedSearchId: Long?, global: Boolean): Long

    suspend fun delete(id: Long)
}
