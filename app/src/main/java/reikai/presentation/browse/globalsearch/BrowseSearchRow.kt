package reikai.presentation.browse.globalsearch

import androidx.compose.runtime.Immutable
import reikai.domain.source.SourceKey

/** Which sources a global search covers. */
enum class SearchSourceFilter { All, PinnedOnly }

/** One source's slice of a global search: it starts loading and ends with results or an error. */
sealed interface EntrySearchState {
    data object Loading : EntrySearchState

    /** Finished; an empty [entries] means the source matched nothing. */
    data class Success(val entries: List<Any>) : EntrySearchState

    data class Error(val message: String?) : EntrySearchState
}

/**
 * One searched source as the shared results list sees it, whatever content type it came from.
 *
 * [source] is the provider's own object, carried opaquely so the shared layer never has to know what
 * a manga source or a plugin looks like; only the leaf that renders that type unwraps it, and so do
 * the entries inside [EntrySearchState.Success].
 */
@Immutable
data class BrowseSearchRow(
    val key: SourceKey,
    val name: String,
    val lang: String,
    val isPinned: Boolean,
    val state: EntrySearchState,
    val source: Any,
)

/**
 * Orders the one results list, across both content types: sources that found something first, then
 * pinned sources, then by name. Re-applied as each source lands, so a source that returns nothing
 * sinks past the ones that did while the rest are still running.
 */
val searchRowComparator: Comparator<BrowseSearchRow> = compareBy(
    { (it.state as? EntrySearchState.Success)?.entries?.isEmpty() ?: true },
    { !it.isPinned },
    { it.name.lowercase() },
)

/**
 * Whether a row survives the "only show sources with results" toggle. A source still loading is
 * hidden by it too, which is what stops the list reshuffling under a reader who turned it on.
 */
fun BrowseSearchRow.hasResults(): Boolean {
    val state = state
    return state is EntrySearchState.Success && state.entries.isNotEmpty()
}
