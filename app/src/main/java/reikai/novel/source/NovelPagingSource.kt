package reikai.novel.source

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import reikai.novel.host.NovelItem
import tachiyomi.core.common.util.lang.withIOContext

/** Pages a source's Popular / Latest listing, with the filter values already encoded in [optionsJson]. */
class NovelListingPagingSource(
    source: NovelSource,
    private val optionsJson: String,
) : BaseNovelPagingSource(source) {
    override suspend fun requestNextPage(page: Int): List<NovelItem> = source.popularNovels(page, optionsJson)
}

/** Pages a source's search results. lnreader search takes no filters, hence no options here. */
class NovelSearchPagingSource(
    source: NovelSource,
    private val query: String,
) : BaseNovelPagingSource(source) {
    override suspend fun requestNextPage(page: Int): List<NovelItem> = source.searchNovels(query, page)
}

/**
 * Paging 3 over a light-novel plugin, the novel twin of `BaseSourcePagingSource`.
 *
 * Two things differ from that twin, both forced by the plugin format. lnreader plugins report no
 * `hasNextPage`, so an empty page is what ends a catalogue. And a plugin that answers an out-of-range
 * page by repeating the last one would page forever, since Paging keeps requesting while the key is
 * non-null, so a page whose every entry has already been seen ends the catalogue too.
 */
abstract class BaseNovelPagingSource(
    protected val source: NovelSource,
) : PagingSource<Long, NovelItem>() {

    private val seenPaths = hashSetOf<String>()

    abstract suspend fun requestNextPage(page: Int): List<NovelItem>

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, NovelItem> {
        val page = params.key ?: 1

        return try {
            val fetched = withIOContext { requestNextPage(page.toInt()) }
            // Dedupe by path so a source repeating entries across a page boundary cannot produce
            // duplicate keys in the grid.
            val fresh = fetched.filter { seenPaths.add(it.path) }
            LoadResult.Page(
                data = fresh,
                prevKey = null,
                nextKey = if (fetched.isEmpty() || fresh.isEmpty()) null else page + 1,
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
