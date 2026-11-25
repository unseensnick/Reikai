package tachiyomi.domain.manga.repository

import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import exh.metadata.sql.models.SearchMetadata
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for adult/EXH gallery metadata (tags, titles, raw extra) keyed by manga id.
 *
 * Ported from Komikku's EXH subsystem. The favorites join is not here: it reads manga rows, so it is
 * [MangaRepository.getExhFavoriteMangaWithMetadata].
 */
interface MangaMetadataRepository {
    suspend fun getMetadataById(id: Long): SearchMetadata?

    fun subscribeMetadataById(id: Long): Flow<SearchMetadata?>

    suspend fun getTagsById(id: Long): List<SearchTag>

    fun subscribeTagsById(id: Long): Flow<List<SearchTag>>

    // Every gallery's tags, re-read when the table changes (library tag search).
    fun subscribeAllTags(): Flow<List<SearchTag>>

    suspend fun getTitlesById(id: Long): List<SearchTitle>

    fun subscribeTitlesById(id: Long): Flow<List<SearchTitle>>

    // Every gallery's titles, re-read when the table changes (library tag search alt-title match).
    fun subscribeAllTitles(): Flow<List<SearchTitle>>

    suspend fun insertFlatMetadata(flatMetadata: FlatMetadata)

    suspend fun insertMetadata(metadata: RaisedSearchMetadata) = insertFlatMetadata(metadata.flatten())

    suspend fun getSearchMetadata(): List<SearchMetadata>
}
