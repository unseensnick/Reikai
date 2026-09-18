package reikai.presentation.browse

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlin.time.Duration
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

/**
 * The query a Browse list filters on: typing waits for [BROWSE_SEARCH_DEBOUNCE], an empty field passes at
 * once. debounce holds the first value too, so without that a cold open waited out the delay unsearched.
 */
@OptIn(FlowPreview::class)
fun Flow<String?>.debouncedBrowseQuery(): Flow<String?> =
    debounce { if (it.isNullOrBlank()) Duration.ZERO else BROWSE_SEARCH_DEBOUNCE }
