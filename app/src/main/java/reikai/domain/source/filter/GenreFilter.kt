package reikai.domain.source.filter

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/** What a genre search runs: [query] null when the genre is picked in [filters]. */
data class GenreSearch<F>(val query: String?, val filters: F)

/**
 * A genre tapped on a details page searches from the source's [defaults], never the filters applied
 * before: with the genre picked when [withGenre] finds a filter for it, otherwise as the text.
 * Shared by the manga and novel catalogues.
 */
fun <F> genreSearch(genre: String, defaults: F, withGenre: (F) -> F?): GenreSearch<F> =
    withGenre(defaults)?.let { GenreSearch(query = null, filters = it) }
        ?: GenreSearch(query = genre, filters = defaults)

/**
 * Turns on the filter named [genre], the way a genre tapped on a details page searches a source:
 * the first entry of a group with that name is included, or the first select offering it moves to
 * it. Names match ignoring case. Returns false, leaving the list as it was, when nothing matches.
 * Upstream's matcher from BrowseSourceViewModel.searchGenre, moved here so novel sources built on
 * the same filter model search a genre the same way.
 */
fun FilterList.selectGenre(genre: String): Boolean {
    for (sourceFilter in this) {
        if (sourceFilter is Filter.Group<*>) {
            for (filter in sourceFilter.state) {
                if (filter is Filter<*> && filter.name.equals(genre, true)) {
                    when (filter) {
                        is Filter.TriState -> filter.state = 1
                        is Filter.CheckBox -> filter.state = true
                        else -> {}
                    }
                    return true
                }
            }
        } else if (sourceFilter is Filter.Select<*>) {
            val index = sourceFilter.values.filterIsInstance<String>()
                .indexOfFirst { it.equals(genre, true) }

            if (index != -1) {
                sourceFilter.state = index
                return true
            }
        }
    }
    return false
}
