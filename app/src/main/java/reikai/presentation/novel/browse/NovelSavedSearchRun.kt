package reikai.presentation.novel.browse

/**
 * What a saved search runs on a novel source: one rule for the feed row and the catalogue it opens,
 * which chose different listings for the same search. Where filters go follows their format, the
 * source's `applyToSearch`: a Mihon filter list travels with the query, as manga's does, while an
 * LNReader plugin's search takes no options, so it runs a query or filters, never both.
 */
internal sealed interface NovelSavedSearchRun {
    /** A search carrying the query and the stored filters. */
    data class SearchWithFilters(val query: String) : NovelSavedSearchRun

    /** The query alone. The stored filters are dropped, since the search could never use them. */
    data class PlainSearch(val query: String) : NovelSavedSearchRun

    /** The stored filters over the Popular listing, the one the catalogue applies filters to. */
    data object FilteredPopular : NovelSavedSearchRun

    companion object {
        fun of(applyToSearch: Boolean, query: String?): NovelSavedSearchRun = when {
            applyToSearch -> SearchWithFilters(query.orEmpty())
            !query.isNullOrBlank() -> PlainSearch(query)
            else -> FilteredPopular
        }
    }
}
