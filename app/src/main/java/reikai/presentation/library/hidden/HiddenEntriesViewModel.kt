package reikai.presentation.library.hidden

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import mihon.core.metro.AppCoroutineScope
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.library.HideSeries
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.includes
import reikai.domain.library.librarySortComparator
import reikai.domain.library.toSortMode
import reikai.domain.library.within
import reikai.domain.novel.NovelRepository
import reikai.presentation.library.MangaLibraryRows
import reikai.presentation.library.gridColumns
import reikai.presentation.library.gridDisplayMode
import reikai.presentation.library.isHiddenFromLibrary
import reikai.presentation.library.mixedLibraryItemSortFields
import reikai.presentation.library.novels.NovelLibraryRows
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionStore
import reikai.presentation.stats.sortedForDisplay
import reikai.presentation.stats.statsDetailsScreen
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences

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
    private val novelRepository: NovelRepository,
    private val libraryPreferences: LibraryPreferences,
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
        combine(mangaLibraryRows.rows(), novelLibraryRows.rows()) { manga, novels -> manga + novels },
        hideSeries.subscribeAll(),
        getEntryCustomInfo.subscribeAll(),
        libraryPreferences.sortingMode.changes(),
        libraryPreferences.randomSortSeed.changes(),
    ) { rows, hidden, customInfo, sort, seed ->
        val fields = mixedLibraryItemSortFields { null }
        val comparator = librarySortComparator(sort.type.toSortMode(), sort.isAscending, seed.toLong(), fields)
        rows.filter { isHiddenFromLibrary(it, hidden) }.sortedForDisplay(comparator, customInfo)
    }.flowOn(Dispatchers.Default)

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

    suspend fun detailsScreen(entryId: EntryId): Screen? = statsDetailsScreen(entryId, novelRepository)

    fun displayMode(): PreferenceMutableState<LibraryDisplayMode> = libraryPreferences.gridDisplayMode(viewModelScope)

    fun columnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> =
        libraryPreferences.gridColumns(viewModelScope, isLandscape)
}

private class ShownRows(val type: ContentType, val rows: List<LibraryItem>)

@Immutable
data class HiddenEntriesState(
    val rows: List<LibraryItem>,
    val contentType: ContentType,
    val selection: Set<EntryId> = emptySet(),
)
