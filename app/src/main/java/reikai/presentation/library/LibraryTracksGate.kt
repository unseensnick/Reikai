package reikai.presentation.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

/** The library's tracks, by entry id, while [libraryNeedsTracks] says something reads them, else none. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> libraryTracksFlow(
    filters: Flow<LibraryFilterSettings>,
    categories: Flow<List<Category>>,
    libraryPreferences: LibraryPreferences,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
    searchQuery: Flow<String?>,
    tracks: () -> Flow<Map<Long, List<T>>>,
): Flow<Map<Long, List<T>>> = combine(
    filters.map { it.trackers.values },
    libraryPreferences.sortingMode.changes(),
    categories,
    reikaiLibraryPreferences.groupLibraryBy.changes(),
    searchQuery.map { query -> query?.takeUnless { it.isBlank() }?.let(QueryNode::from)?.readsTracks() == true },
    ::libraryNeedsTracks,
)
    .distinctUntilChanged()
    .flatMapLatest { needsTracks -> if (needsTracks) tracks() else flowOf(emptyMap()) }
