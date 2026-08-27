package reikai.presentation.browse.catalogue

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * The neutral behaviour both content types expose to the shared catalogue screen: the state stream,
 * the paged rows, and the verbs the toolbar, chip row, grid and dialogs call. Only actions both types
 * answer for real live here, so the spine never grows a method one adapter no-ops. What one type
 * alone can do goes on a capability slot instead. Navigation is not here either: the screen routes on
 * the source key, the way the shared global search does.
 */
interface EntryBrowseBehavior {
    val state: StateFlow<EntryBrowseScreenState>

    /** The paged catalogue. The outer flow re-emits when the listing changes, which is what restarts
     *  paging; this is the shape `collectAsLazyPagingItems` takes. */
    val rows: StateFlow<Flow<PagingData<EntryBrowseRow>>>

    // Listing and search.
    fun setListing(listing: EntryBrowseListing)

    /** The in-toolbar text as it is typed. Does not run a search. */
    fun setQuery(query: String?)

    /** Commit [query] as the listing, or clear the search when it is null or blank. */
    fun search(query: String?)

    fun setDisplayMode(mode: LibraryDisplayMode)

    // Filters. The sheet itself is dispatched per type; these are the verbs both sides share.
    fun openFilterSheet()
    fun resetFilters()

    // Rows and bulk selection. A row is passed whole so each adapter unwraps its own payload rather
    // than the shared layer keeping a key-to-entry table beside the pager.
    fun onRowLongClick(row: EntryBrowseRow)
    fun setSelectionMode(enabled: Boolean)
    fun toggleSelection(row: EntryBrowseRow)
    fun selectAll(rows: List<EntryBrowseRow>)
    fun invertSelection(rows: List<EntryBrowseRow>)

    /** Add every selected row to the library, which raises the category dialog. */
    fun addSelectionToLibrary()

    /** Apply the bulk category choice to the whole selection. */
    fun setSelectionCategories(categoryIds: List<Long>)

    // Dialogs. The entry each one acts on is the one the adapter's own state already remembers.
    fun dismissDialog()

    /** Confirm [EntryBrowseDialog.Remove], taking the entry out of the library. */
    fun confirmRemove()

    /** Confirm the category picker for whichever entry raised it. */
    fun confirmCategories(categoryIds: List<Long>)

    /** Add the entry anyway, despite the duplicates the dialog listed. */
    fun confirmAddDuplicate()

    /** Add the entry to the groups the picked duplicates belong to. */
    fun addToGroup(entryIds: List<Long>)

    /** Migrate the duplicate at [duplicateId] onto the entry the dialog was raised for. */
    fun startMigrate(duplicateId: Long)
}
