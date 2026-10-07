package reikai.novel.source

import androidx.paging.PagingSource
import androidx.paging.PagingState
import reikai.domain.source.CataloguePaging
import reikai.domain.source.catalogueRefreshKey
import reikai.novel.host.NovelItem
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.source.NoResultsException

/** Pages a source's Popular / Latest listing. */
class NovelListingPagingSource(
    source: NovelSource,
    private val listing: NovelListing,
    private val filters: NovelFilterState?,
) : BaseNovelPagingSource(source) {
    override suspend fun requestNextPage(page: Int): NovelItemsPage = source.browse(listing, page, filters)
}

/** Pages a source's search results. */
class NovelSearchPagingSource(
    source: NovelSource,
    private val query: String,
    private val filters: NovelFilterState?,
) : BaseNovelPagingSource(source) {
    override suspend fun requestNextPage(page: Int): NovelItemsPage = source.search(query, page, filters)
}

/** Paging 3 over a novel source; twin of `BaseSourcePagingSource`, pinned by `CataloguePaging`. */
abstract class BaseNovelPagingSource(
    protected val source: NovelSource,
) : PagingSource<Long, NovelItem>() {

    private val paging = CataloguePaging<NovelItem> { it.path }

    abstract suspend fun requestNextPage(page: Int): NovelItemsPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, NovelItem> {
        val page = params.key ?: 1

        return runCatchingCancellable {
            val fetched = withIOContext { requestNextPage(page.toInt()) }
            val taken = paging.take(page, params is LoadParams.Refresh, fetched.items, fetched.end)
                ?: throw NoResultsException()
            LoadResult.Page(data = taken.fresh, prevKey = null, nextKey = taken.nextKey)
        }.getOrElse { LoadResult.Error(it) }
    }

    override fun getRefreshKey(state: PagingState<Long, NovelItem>): Long? = state.catalogueRefreshKey()
}
