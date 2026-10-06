package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.CancellationException
import mihon.domain.manga.model.toDomainManga
import reikai.domain.source.CatalogueEnd
import reikai.domain.source.CataloguePaging
import reikai.domain.source.catalogueRefreshKey
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource

class SourceSearchPagingSource(
    source: suspend () -> Source,
    private val query: String,
    private val filters: FilterList,
    networkToLocalManga: NetworkToLocalManga,
) : BaseSourcePagingSource(source, networkToLocalManga) {
    override suspend fun requestNextPage(source: Source, currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(
    source: suspend () -> Source,
    networkToLocalManga: NetworkToLocalManga,
) : BaseSourcePagingSource(source, networkToLocalManga) {
    override suspend fun requestNextPage(source: Source, currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(
    source: suspend () -> Source,
    networkToLocalManga: NetworkToLocalManga,
) : BaseSourcePagingSource(source, networkToLocalManga) {
    override suspend fun requestNextPage(source: Source, currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    private val source: suspend () -> Source,
    private val networkToLocalManga: NetworkToLocalManga,
) : SourcePagingSource() {

    // RK: seenManga moved into CataloguePaging, which owns dedupe, the list's end and the refresh key
    //     for the manga and novel catalogues alike.
    private val paging = CataloguePaging<Pair<Manga, RaisedSearchMetadata?>> { it.first.url }

    abstract suspend fun requestNextPage(source: Source, currentPage: Int): MangasPage

    // RK: element type is Pair<Manga, RaisedSearchMetadata?> so a metadata source (E-Hentai) can
    //     pair each gallery with its parsed metadata for the rich browse rows; other sources pair
    //     with null.
    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Pair<Manga, RaisedSearchMetadata?>> {
        val page = params.key ?: 1

        return try {
            val source = source()
            // RK: an empty page is judged by CataloguePaging below: no results only on a first load
            val mangasPage = withIOContext { requestNextPage(source, page.toInt()) }

            // RK: pair each manga with its metadata by index before the dedup filter, then re-zip
            //     after networkToLocalManga (which preserves order). Non-metadata pages -> null.
            val metadata = (mangasPage as? MetadataMangasPage)?.mangasMetadata ?: emptyList()
            // RK -->
            val taken = paging.take(
                page = page,
                isFirstLoad = params is LoadParams.Refresh,
                items = mangasPage.mangas
                    .mapIndexed { index, sManga -> sManga.toDomainManga(source.id) to metadata.getOrNull(index) },
                end = CatalogueEnd.Reported(mangasPage.hasNextPage),
            ) ?: throw NoResultsException()
            val manga = taken.fresh
                .let { pairs -> networkToLocalManga(pairs.map { it.first }).zip(pairs.map { it.second }) }
            // RK <--

            LoadResult.Page(
                data = manga,
                prevKey = null,
                // RK: a metadata source (E-Hentai) supplies its own paging cursor (the gallery id);
                //     use it instead of a page-number increment so browse loads past the first page.
                //     Other sources have no carrier and fall through to the page + 1 behaviour.
                nextKey = taken.nextKey?.let { (mangasPage as? MetadataMangasPage)?.nextKey ?: it },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        } catch (e: LinkageError) {
            // RK: an extension built against a class or method the app no longer ships throws a
            //     LinkageError; uncaught, it crashes the app instead of showing a load error.
            LoadResult.Error(e)
        }
    }

    // RK: the paired element type load() pages, reloading where CataloguePaging says
    override fun getRefreshKey(state: PagingState<Long, Pair<Manga, RaisedSearchMetadata?>>): Long? {
        return state.catalogueRefreshKey()
    }
}

class NoResultsException : Exception()
