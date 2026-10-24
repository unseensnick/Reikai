package reikai.presentation.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import mihon.domain.library.model.search.QueryNode
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.sortForCategory
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Whether a library reads its tracks at all. Only a tracker filter, a tracker-score sort (the global
 * one or a category's own), grouping by tracking status and a search for a tracker or a score read them,
 * and loading every track is slow on a large library. Mihon's gate has the first two.
 */
fun libraryNeedsTracks(
    trackerFilters: Collection<TriState>,
    globalSort: LibrarySort,
    categories: List<Category>,
    groupBy: Int,
    searchReadsTracks: Boolean = false,
): Boolean = searchReadsTracks ||
    trackerFilters.any { it != TriState.DISABLED } ||
    groupBy == LibraryGroup.BY_TRACK_STATUS ||
    globalSort.type == LibrarySort.Type.TrackerMean ||
    categories.any { sortForCategory(it, globalSort).type == LibrarySort.Type.TrackerMean }

/** What a library filters and searches on: everything it reads from [libraryTracksFlow], and nothing else. */
data class LibrarySearchInputs<Q, T>(
    val search: Q,
    val filters: LibraryFilterSettings,
    /** Each `category:` term's matching category ids, resolved against the current category table. */
    val categoryMatches: Map<String, Set<Long>>,
    val tracks: Map<Long, List<T>>,
)

/**
 * Each [search] (the debounced query, with whatever the caller resolved for it) with the library's
 * [filters], its `category:` matches and its tracks, by entry id, while [libraryNeedsTracks] says something
 * reads them, else none. A value is held back until the tracks it needs have loaded, so a library never
 * filters a tracker filter or query against the wrong tracks, and a sort, grouping or category change that
 * alters none of these sends nothing, so it does not rebuild a library that would come out the same.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <Q, T> libraryTracksFlow(
    filters: Flow<LibraryFilterSettings>,
    categories: Flow<List<Category>>,
    libraryPreferences: LibraryPreferences,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
    search: Flow<Q>,
    queryOf: (Q) -> String?,
    tracks: () -> Flow<Map<Long, List<T>>>,
): Flow<LibrarySearchInputs<Q, T>> = channelFlow {
    // Shared, since the tracks' loading and the pairing both read it, and the caller's search can query.
    val inputs = combine(
        filters,
        libraryPreferences.sortingMode.changes(),
        categories,
        reikaiLibraryPreferences.groupLibraryBy.changes(),
        search,
    ) { filterSettings, sort, categoryList, groupBy, query ->
        val queryNode = queryOf(query)?.takeUnless { it.isBlank() }?.let(QueryNode::from)
        val needsTracks = libraryNeedsTracks(
            filterSettings.trackers.values,
            sort,
            categoryList,
            groupBy,
            queryNode?.readsTracks() == true,
        )
        val categoryMatches = queryNode?.categoryMatches(categoryList).orEmpty()
        LibrarySearchInputs<Q, T>(query, filterSettings, categoryMatches, emptyMap()) to needsTracks
    }.shareIn(this, SharingStarted.Eagerly, replay = 1)
    // Loaded once while needed, not once per search, and tagged with the answer it was loaded for.
    val loaded = inputs.map { it.second }
        .distinctUntilChanged()
        .flatMapLatest { needsTracks -> if (needsTracks) tracks().map { true to it } else flowOf(false to emptyMap()) }
    combine(inputs, loaded) { (searchInputs, needsTracks), (loadedFor, trackMap) ->
        searchInputs.copy(tracks = trackMap).takeIf { needsTracks == loadedFor }
    }
        .filterNotNull()
        .distinctUntilChanged()
        .collect { send(it) }
}
