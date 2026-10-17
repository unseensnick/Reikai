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

/**
 * Each [search] (the debounced query, with whatever the caller resolved for it) paired with the library's
 * tracks, by entry id, while [libraryNeedsTracks] says something reads them, else none. A search is held
 * back until the tracks it needs have loaded, and a search that stops needing them arrives with none, so a
 * library never filters a tracker or score query against the wrong tracks.
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
): Flow<Pair<Q, Map<Long, List<T>>>> = channelFlow {
    // Shared, since the tracks' loading and the pairing both read it, and the caller's search can query.
    val inputs = combine(
        filters.map { it.trackers.values },
        libraryPreferences.sortingMode.changes(),
        categories,
        reikaiLibraryPreferences.groupLibraryBy.changes(),
        search,
    ) { trackers, sort, categoryList, groupBy, query ->
        val readsTracks = queryOf(query)?.takeUnless { it.isBlank() }?.let(QueryNode::from)?.readsTracks() == true
        query to libraryNeedsTracks(trackers, sort, categoryList, groupBy, readsTracks)
    }.shareIn(this, SharingStarted.Eagerly, replay = 1)
    // Loaded once while needed, not once per search, and tagged with the answer it was loaded for.
    val loaded = inputs.map { it.second }
        .distinctUntilChanged()
        .flatMapLatest { needsTracks -> if (needsTracks) tracks().map { true to it } else flowOf(false to emptyMap()) }
    combine(inputs, loaded) { (query, needsTracks), (loadedFor, trackMap) ->
        (query to trackMap).takeIf { needsTracks == loadedFor }
    }
        .filterNotNull()
        .collect { send(it) }
}
