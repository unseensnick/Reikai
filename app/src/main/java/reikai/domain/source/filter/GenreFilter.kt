package reikai.domain.source.filter

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

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
