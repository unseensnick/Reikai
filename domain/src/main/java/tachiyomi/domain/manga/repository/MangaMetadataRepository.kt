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
 * Ported from Komikku's EXH subsystem. The favorites-join queries
 * (getExhFavoriteMangaWithMetadata / getIdsOfFavoriteMangaWithMetadata) are deferred to the
 * E-Hentai favorites-sync phase, since they depend on the E-Hentai source ids and custom
 * mangas.sq queries that ship with that phase.
 */
interface MangaMetadataRepository {
    suspend fun getMetadataById(id: Long): SearchMetadata?

    fun subscribeMetadataById(id: Long): Flow<SearchMetadata?>

    suspend fun getTagsById(id: Long): List<SearchTag>

    fun subscribeTagsById(id: Long): Flow<List<SearchTag>>

    // Batch-load every gallery's tags in one query (library inverted tag search).
    suspend fun getAllTags(): List<SearchTag>

    suspend fun getTitlesById(id: Long): List<SearchTitle>

    fun subscribeTitlesById(id: Long): Flow<List<SearchTitle>>

    // Batch-load every gallery's titles in one query (library tag search alt-title match).
    suspend fun getAllTitles(): List<SearchTitle>

    suspend fun insertFlatMetadata(flatMetadata: FlatMetadata)

    suspend fun insertMetadata(metadata: RaisedSearchMetadata) = insertFlatMetadata(metadata.flatten())

    suspend fun getSearchMetadata(): List<SearchMetadata>
}
