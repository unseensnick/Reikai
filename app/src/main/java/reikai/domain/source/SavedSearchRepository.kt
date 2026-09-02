package reikai.domain.source

import kotlinx.coroutines.flow.Flow
import reikai.domain.source.model.SavedSearch

/**
 * Storage for named browse filter presets, shared by both content types: a saved search is identified
 * by a [SourceKey], so one table serves manga sources and light-novel plugins alike.
 *
 * A row whose stored source cannot be read back as a [SourceKey] is left out rather than surfaced,
 * because one corrupt row must not blank a source's whole chip list.
 */
interface SavedSearchRepository {

    suspend fun getBySource(sourceKey: SourceKey): List<SavedSearch>

    /** [getBySource] as a live list, for the chips that must appear the moment a search is saved. */
    fun subscribeBySource(sourceKey: SourceKey): Flow<List<SavedSearch>>

    /** Every saved search, for backup. */
    suspend fun getAll(): List<SavedSearch>

    /** Inserts and returns the new row id. */
    suspend fun insert(sourceKey: SourceKey, name: String, query: String?, filtersJson: String?): Long

    /** Deletes the search, and with it any feed row built on it (the FK cascades). */
    suspend fun delete(id: Long)
}
