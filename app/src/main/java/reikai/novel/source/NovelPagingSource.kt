package reikai.novel.source

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import reikai.novel.host.NovelItem
import tachiyomi.core.common.util.lang.withIOContext

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

/**
 * Paging 3 over a novel source, the novel twin of `BaseSourcePagingSource`.
 *
 * A catalogue ends where the source says it does, and also at a page whose every entry has already been
 * seen: a plugin that answers an out-of-range page by repeating the last one would otherwise page
 * forever, since Paging keeps requesting while the key is non-null.
 */
abstract class BaseNovelPagingSource(
    protected val source: NovelSource,
) : PagingSource<Long, NovelItem>() {

    private val seenPaths = hashSetOf<String>()

    abstract suspend fun requestNextPage(page: Int): NovelItemsPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, NovelItem> {
        val page = params.key ?: 1

        return try {
            val fetched = withIOContext { requestNextPage(page.toInt()) }
            // Dedupe by path so a source repeating entries across a page boundary cannot produce
            // duplicate keys in the grid.
            val fresh = fetched.items.filter { seenPaths.add(it.path) }
            LoadResult.Page(
                data = fresh,
                prevKey = null,
                nextKey = if (!fetched.hasNextPage || fresh.isEmpty()) null else page + 1,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, NovelItem>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}
