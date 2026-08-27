package reikai.presentation.browse.source

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * The neutral behaviour both content types expose to the shared per-source browse screen: the state
 * stream, the paged rows, and the verbs the toolbar, chip row, grid and dialogs call. Only genuinely
 * shared actions live here, so the spine never grows a method one adapter answers with nothing.
 * Navigation is not here either: the screen routes on the source key, the way the shared global
 * search does, because the two types open different destinations.
 */
interface EntryBrowseBehavior {
    val state: StateFlow<EntryBrowseScreenState>

    /** The paged catalogue. Re-collected when the listing changes, so a chip switch restarts paging. */
    val rows: Flow<PagingData<EntryBrowseRow>>

    // Listing and search.
    fun setListing(listing: EntryBrowseListing)

    /** The in-toolbar text as it is typed; does not run a search. */
    fun setQuery(query: String?)

    /** Commit [query] as the listing, or the current toolbar text when null. */
    fun search(query: String?)

    /** Search a genre the details screen tapped through to. Both types page a genre as a query. */
    fun searchGenre(genre: String)

    fun setDisplayMode(mode: LibraryDisplayMode)

    /**
     * Re-run the listing. Also re-resolves the source, which is what recovers
     * [EntryBrowseScreenState.SourceFailed]; the manga side has nothing to re-resolve and just pages
     * again. One verb rather than a novel-only retry, so neither adapter carries a dead method.
     */
    fun refresh()

    // Filters. The sheet itself is dispatched per type; these are the verbs both sides share.
    fun openFilterSheet()
    fun resetFilters()

    // Rows and bulk selection. Keys are [EntryBrowseRow.key].
    fun onRowLongClick(key: String)
    fun setSelectionMode(enabled: Boolean)
    fun toggleSelection(key: String)
    fun selectAll(keys: List<String>)
    fun invertSelection(keys: List<String>)

    /** Add every selected row to the library, which raises the category dialog. */
    fun addSelectionToLibrary()

    // Dialogs. Ids are neutral; each adapter fans them back out to its own model.
    fun setDialog(dialog: EntryBrowseDialog?)

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

    /** Report a browse pick back to the caller that opened this screen to choose a migration target,
     *  which only [EntryBrowseCapabilities.migrationPick] declares. */
    fun pickAsMigrationTarget(key: String)
}
