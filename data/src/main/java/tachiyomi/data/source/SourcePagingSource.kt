package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import exh.metadata.metadata.RaisedSearchMetadata
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSearchPagingSource(
    source: Source,
    private val query: String,
    private val filters: FilterList,
) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: Source,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : SourcePagingSource() {

    private val seenManga = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    // RK: element type is Pair<Manga, RaisedSearchMetadata?> so a metadata source (E-Hentai) can
    //     pair each gallery with its parsed metadata for the rich browse rows; other sources pair
    //     with null.
    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Pair<Manga, RaisedSearchMetadata?>> {
        val page = params.key ?: 1

        return try {
            val mangasPage = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.mangas.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            // RK: pair each manga with its metadata by index before the dedup filter, then re-zip
            //     after networkToLocalManga (which preserves order). Non-metadata pages -> null.
            val metadata = (mangasPage as? MetadataMangasPage)?.mangasMetadata ?: emptyList()
            val manga = mangasPage.mangas
                .mapIndexed { index, sManga -> sManga.toDomainManga(source.id) to metadata.getOrNull(index) }
                .filter { seenManga.add(it.first.url) }
                .let { pairs -> networkToLocalManga(pairs.map { it.first }).zip(pairs.map { it.second }) }

            LoadResult.Page(
                data = manga,
                prevKey = null,
                // RK: a metadata source (E-Hentai) supplies its own paging cursor (the gallery id);
                //     use it instead of a page-number increment so browse loads past the first page.
                //     Other sources have no carrier and fall through to the page + 1 behaviour.
                nextKey = (mangasPage as? MetadataMangasPage)?.nextKey
                    ?: if (mangasPage.hasNextPage) page + 1 else null,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Pair<Manga, RaisedSearchMetadata?>>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}

class NoResultsException : Exception()
