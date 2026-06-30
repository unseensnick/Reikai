package tachiyomi.domain.source.repository

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.model.FilterList
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.SourceWithCount

// RK: browse paging carries (manga, metadata) so adult sources can render rich rows.
typealias SourcePagingSource = PagingSource<Long, Pair<Manga, RaisedSearchMetadata?>>

interface SourceRepository {

    fun getSources(): Flow<List<Source>>

    fun getOnlineSources(): Flow<List<Source>>

    fun getSourcesWithFavoriteCount(): Flow<List<Pair<Source, Long>>>

    fun getSourcesWithNonLibraryManga(): Flow<List<SourceWithCount>>

    fun search(sourceId: Long, query: String, filterList: FilterList): SourcePagingSource

    fun getPopular(sourceId: Long): SourcePagingSource

    fun getLatest(sourceId: Long): SourcePagingSource
}
