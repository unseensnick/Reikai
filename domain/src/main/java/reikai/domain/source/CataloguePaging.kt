package reikai.domain.source

import androidx.paging.PagingState

/** How a source marks the end of its catalogue. */
sealed interface CatalogueEnd {

    /** The source says whether another page follows, as every manga source and novel app does. */
    data class Reported(val hasNextPage: Boolean) : CatalogueEnd

    /**
     * The source cannot say (an LNReader plugin), so the catalogue ends at a page that brings nothing new:
     * a plugin answering an out-of-range page by repeating the last one would otherwise page forever.
     */
    data object Inferred : CatalogueEnd
}

/** One fetched page with the entries already listed taken out, and the key of the page after it. */
data class CataloguePage<T>(val fresh: List<T>, val nextKey: Long?)

/**
 * The paging rule every catalogue grid follows, manga and novel alike. Entries are told apart by [keyOf],
 * so one a source repeats across a page boundary is listed once. A first load that comes back empty is no
 * results, which the screen says; a later empty page is the end of the list, ended quietly.
 */
class CataloguePaging<T>(private val keyOf: (T) -> String) {

    private val seen = hashSetOf<String>()

    /** [items] as fetched for [page]; null when [isFirstLoad] and the source returned nothing. */
    fun take(page: Long, isFirstLoad: Boolean, items: List<T>, end: CatalogueEnd): CataloguePage<T>? {
        if (items.isEmpty() && isFirstLoad) return null
        val fresh = items.filter { seen.add(keyOf(it)) }
        val hasNext = items.isNotEmpty() &&
            when (end) {
                is CatalogueEnd.Reported -> end.hasNextPage
                CatalogueEnd.Inferred -> fresh.isNotEmpty()
            }
        return CataloguePage(fresh, nextKey = if (hasNext) page + 1 else null)
    }
}

/** Where a catalogue reloads from: the key beside the page the reader was last looking at. */
fun <T : Any> PagingState<Long, T>.catalogueRefreshKey(): Long? {
    return anchorPosition?.let { anchorPosition ->
        val anchorPage = closestPageToPosition(anchorPosition)
        anchorPage?.prevKey ?: anchorPage?.nextKey
    }
}
