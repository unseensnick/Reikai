package reikai.presentation.browse

import kotlin.time.Duration.Companion.seconds

/**
 * Whether a Browse list row survives the search box. Every comma-separated part is tried on its own
 * and any hit keeps the row, so "french, korean" widens rather than narrows. [terms] match as
 * substrings; [ids] only whole, so a typed number does not pull in every id containing it.
 */
fun matchesBrowseQuery(query: String?, terms: List<String>, ids: List<String>): Boolean {
    val subqueries = query.orEmpty().split(",").map { it.trim() }.filterNot { it.isBlank() }
    if (subqueries.isEmpty()) return true
    return subqueries.any { subquery ->
        terms.any { it.contains(subquery, ignoreCase = true) } || subquery in ids
    }
}

/** How long a Browse list waits after the last keystroke before filtering on the query. */
val BROWSE_SEARCH_DEBOUNCE = 0.25.seconds
