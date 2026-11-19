package reikai.presentation.library.hidden

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import mihon.core.metro.AppCoroutineScope
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.library.HideSeries
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.includes
import reikai.domain.library.librarySortComparator
import reikai.domain.library.toSortMode
import reikai.domain.library.within
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.toUiTrack
import reikai.presentation.library.EntryTracks
import reikai.presentation.library.MangaLibraryRows
import reikai.presentation.library.gridColumns
import reikai.presentation.library.gridDisplayMode
import reikai.presentation.library.isHiddenFromLibrary
import reikai.presentation.library.meansOf
import reikai.presentation.library.mixedLibraryItemSortFields
import reikai.presentation.library.novels.NovelLibraryRows
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionStore
import reikai.presentation.stats.sortedForDisplay
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.interactor.GetTracksPerManga

/**
 * The series hidden from the library view, both content types switched by the All / Manga / Novels chip,
 * drawn as the library draws them: its own rows before its filters and search, in its display mode and
 * global sort. Unhide puts a series back in the library.
 */
@AssistedInject
class HiddenEntriesViewModel(
    @Assisted initialContentType: ContentType,
    mangaLibraryRows: MangaLibraryRows,
    novelLibraryRows: NovelLibraryRows,
    getEntryCustomInfo: GetEntryCustomInfo,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val hideSeries: HideSeries,
    private val libraryPreferences: LibraryPreferences,
    private val getTracksPerManga: GetTracksPerManga,
    private val getNovelTracks: GetNovelTracks,
    private val trackerManager: TrackerManager,
    // An unhide must land even when the screen closes right after it.
    @AppCoroutineScope private val appScope: CoroutineScope,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialContentType: ContentType): HiddenEntriesViewModel
    }

    // The chip as picked; read through the Show setting live, as the Update errors screen does.
    private val chip = MutableStateFlow(initialContentType)
    private val selectionStore = SelectionStore<EntryId>()

    private val hiddenRows = combine(
        combine(mangaLibraryRows.rows, novelLibraryRows.rows) { manga, novels -> manga + novels.items },
        hideSeries.subscribeAll(),
        getEntryCustomInfo.subscribeAll(),
        sorting(),
    ) { rows, hidden, customInfo, sorting ->
        hiddenRowsInOrder(rows, hidden, customInfo, sorting.sort, sorting.seed, sorting.tracks)
    }.flowOn(Dispatchers.Default)

    // Tracks are read only under the tracker-score sort, the one sort that needs them.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun sorting(): Flow<Sorting> = libraryPreferences.sortingMode.changes().flatMapLatest { sort ->
        val tracks = if (sort.type == LibrarySort.Type.TrackerMean) {
            combine(
                getTracksPerManga.subscribe(),
                getNovelTracks.subscribeAll(),
                trackerManager.loggedInTrackersFlow(),
            ) { manga, novels, trackers ->
                EntryTracks(manga, novels.mapValues { (_, tracks) -> tracks.map(NovelTrack::toUiTrack) }, trackers)
            }
        } else {
            flowOf(null)
        }
        combine(libraryPreferences.randomSortSeed.changes(), tracks) { seed, tracks ->
            Sorting(sort, seed.toLong(), tracks)
        }
    }

    // A series unhidden or removed leaves the selection with its row.
    private val shownRows = combine(hiddenRows, chip, reikaiLibraryPreferences.shownContent.changes()) {
            rows,
            chip,
            shown,
        ->
        val type = chip.within(shown)
        ShownRows(type, rows.filter { type.includes(it.entryId.contentType) })
    }
        .onEach { shown -> selectionStore.retain(shown.rows.map { it.entryId }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null while the rows load. */
    val state: StateFlow<HiddenEntriesState?> = combine(shownRows, selectionStore.selection) { shown, selection ->
        shown?.let { HiddenEntriesState(it.rows, it.type, selection) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setContentType(type: ContentType) {
        val from = shownRows.value?.type
        selectionStore.update { EntrySelection.afterChipFlip(it, from, type) }
        chip.value = type
    }

    fun toggleSelection(id: EntryId) = selectionStore.update { EntrySelection.toggle(it, id) }

    fun rangeSelection(id: EntryId) = selectionStore.update { EntrySelection.rangeOrToggle(it, id, orderedIds()) }

    fun selectAll() = selectionStore.update { EntrySelection.selectAll(it, orderedIds()) }

    fun clearSelection() = selectionStore.update { EntrySelection.clear() }

    private fun orderedIds(): List<EntryId> = shownRows.value?.rows.orEmpty().map { it.entryId }

    /** Shows the selected series in the library again, every library member of each one's group. */
    fun unhideSelected() {
        val ids = selectionStore.selection.value
        clearSelection()
        if (ids.isNotEmpty()) appScope.launchIO { hideSeries.unhide(ids) }
    }

    fun displayMode(): PreferenceMutableState<LibraryDisplayMode> = libraryPreferences.gridDisplayMode(viewModelScope)

    fun columnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> =
        libraryPreferences.gridColumns(viewModelScope, isLandscape)
}

/** The hidden rows of [rows] in the library's global sort; [tracks] is null unless the sort scores by tracker. */
internal fun hiddenRowsInOrder(
    rows: List<LibraryItem>,
    hidden: Set<EntryId>,
    customInfo: Map<EntryId, EntryCustomInfo>,
    sort: LibrarySort,
    seed: Long,
    tracks: EntryTracks?,
): List<LibraryItem> {
    val shown = rows.filter { isHiddenFromLibrary(it, hidden) }
    val means = tracks?.meansOf(shown).orEmpty()
    val fields = mixedLibraryItemSortFields { means[it.entryId] }
    val comparator = librarySortComparator(sort.type.toSortMode(), sort.isAscending, seed, fields)
    return shown.sortedForDisplay(comparator, customInfo)
}

private class Sorting(val sort: LibrarySort, val seed: Long, val tracks: EntryTracks?)

private class ShownRows(val type: ContentType, val rows: List<LibraryItem>)

@Immutable
data class HiddenEntriesState(
    val rows: List<LibraryItem>,
    val contentType: ContentType,
    val selection: Set<EntryId> = emptySet(),
)
