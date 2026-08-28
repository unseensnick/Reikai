package reikai.presentation.browse.catalogue

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.novel.host.NovelItem
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.toEntryBrowseUi
import reikai.presentation.novel.browse.NovelBrowseDialog
import reikai.presentation.novel.browse.NovelBrowseState
import reikai.presentation.novel.browse.NovelBrowseViewModel
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.novel.browse.SelectedNovel
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * Adapts the live [NovelBrowseViewModel] plus its bulk-selection model to the neutral
 * [EntryBrowseBehavior]. Symmetric with [MangaBrowseAdapter], so one shared browse screen drives both
 * catalogues. [migrateForId] is set only when the screen was opened to choose a migration target,
 * which is what fills the matching capability slot.
 */
class NovelBrowseAdapter(
    private val model: NovelBrowseViewModel,
    private val bulk: NovelBulkFavoriteViewModel,
    private val sourceId: String,
    migrateForId: Long? = null,
    private val onMigrationPicked: () -> Unit = {},
) : EntryBrowseBehavior {

    // Held once so an emission carrying either does not break the state's equality.
    private val reloadSource: () -> Unit = { model.retryLoadSource() }

    /**
     * The dialog each verb acts on, kept as it is mapped rather than read back off the model.
     *
     * The shared dialogs dismiss before they call back, and dismissing clears the model's own
     * dialog, so a verb reading it there finds nothing and silently does nothing. Never stale: a
     * dialog cannot reach the screen without being mapped here first.
     */
    @Volatile private var raisedDialog: NovelBrowseDialog? = null

    @Volatile private var raisedBulkDialog: EntryBulkFavoriteViewModel.Dialog<SelectedNovel>? = null

    private val capabilities = EntryBrowseCapabilities(
        migrationPick = migrateForId?.let { id ->
            MigrationPickCapability(id) { row, onPicked ->
                model.pickAsMigrationTarget(row.item, id) {
                    onPicked()
                    onMigrationPicked()
                }
            }
        },
    )

    /**
     * The in-toolbar text, which the model has no field for: its own `query` is the committed one.
     * Until the screen types, that committed query stands in, so a source opened with one shows the
     * field holding it. Seeding at construction would read too early: the model applies its initial
     * query only once the plugin has resolved.
     */
    private val toolbarText = MutableStateFlow<ToolbarText>(ToolbarText.Untouched)

    override val state: StateFlow<EntryBrowseScreenState> =
        combine(model.state, bulk.state, toolbarText, ::toNeutral)
            // WhileSubscribed, not Eagerly: the adapter is rebuilt per composition entry while its
            // sharing coroutine lives in the model's scope, so an eager start leaves one orphaned
            // mapper running per re-entry.
            .stateIn(
                model.viewModelScope,
                SharingStarted.WhileSubscribed(),
                toNeutral(model.state.value, bulk.state.value, toolbarText.value),
            )

    override val rows: StateFlow<Flow<PagingData<EntryBrowseRow>>> = model.novelPagerFlowFlow
        .map { pagerFlow ->
            pagerFlow.map { pagingData ->
                pagingData.map { item ->
                    EntryBrowseRow(
                        key = rowKey(sourceId, item),
                        // A browse result carries no library state of its own, so the cell reads it
                        // off the model, which is what re-renders the badge when the entry is added.
                        content = model.state.mapState { it.rowContent(item) },
                    )
                }
            }
        }
        .stateIn(model.viewModelScope, SharingStarted.WhileSubscribed(), emptyFlow())

    private fun NovelBrowseState.rowContent(item: NovelItem) = EntryBrowseRowContent(
        ui = item.toEntryBrowseUi(
            inLibrary = (sourceId to item.path) in favoritedKeys,
            site = source?.site,
        ),
        payload = item,
    )

    private fun toNeutral(
        state: NovelBrowseState,
        bulkState: EntryBulkFavoriteViewModel.State<SelectedNovel>,
        toolbar: ToolbarText,
    ): EntryBrowseScreenState {
        state.dialog?.let { raisedDialog = it }
        bulkState.dialog?.let { raisedBulkDialog = it }
        val source = state.source
            ?: return state.sourceError
                ?.let { EntryBrowseScreenState.SourceFailed(it, reloadSource) }
                ?: EntryBrowseScreenState.Loading
        val searching = state.query.isNotBlank()
        return EntryBrowseScreenState.Loaded(
            sourceName = source.name,
            listing = when {
                searching -> EntryBrowseListing.Search(state.query)
                state.listing == NovelBrowseState.Listing.Latest -> EntryBrowseListing.Latest
                else -> EntryBrowseListing.Popular
            },
            query = when (toolbar) {
                ToolbarText.Untouched -> state.query.takeIf { it.isNotBlank() }
                is ToolbarText.Typed -> toolbar.value
            },
            isUserQuery = searching,
            supportsLatest = source.supportsLatest,
            hasFilters = source.filters?.isNotEmpty() == true,
            filtersActive = searching || state.filtersApplied,
            hasSettings = source.pluginSettings != null,
            webUrl = source.site.takeIf { it.isNotBlank() },
            rowStyle = EntryBrowseRowStyle.Standard(state.displayMode),
            selectionMode = bulkState.selectionMode,
            selectedKeys = bulkState.selection.mapTo(mutableSetOf()) { rowKey(it.sourceId, it.item) },
            capabilities = capabilities,
            // One dialog channel: the bulk category picker only ever opens while an entry dialog is
            // closed, so it rides the same slot rather than needing a second one in the state.
            dialog = state.toNeutralDialog() ?: bulkState.dialog?.toNeutral(),
        )
    }

    private fun EntryBulkFavoriteViewModel.Dialog<SelectedNovel>.toNeutral(): EntryBrowseDialog =
        when (this) {
            is EntryBulkFavoriteViewModel.Dialog.ChangeCategory ->
                EntryBrowseDialog.SelectionCategories(initialSelection)
        }

    private fun NovelBrowseState.toNeutralDialog(): EntryBrowseDialog? = when {
        filterSheetOpen -> EntryBrowseDialog.Filter
        else -> when (val dialog = dialog) {
            null -> null
            is NovelBrowseDialog.RemoveNovel -> EntryBrowseDialog.Remove(dialog.item.name)
            is NovelBrowseDialog.ChangeCategory ->
                EntryBrowseDialog.ChangeCategory(dialog.initialSelection)
            is NovelBrowseDialog.AddDuplicate -> EntryBrowseDialog.AddDuplicate(
                duplicates = dialog.duplicates.map {
                    it.toDuplicateCard(dialog.sourceLabels, dialog.sourceSites)
                },
                groupIdByEntryId = dialog.groupIdByNovelId,
                suggestGroup = dialog.suggestGroup,
            )
            is NovelBrowseDialog.Migrate ->
                EntryBrowseDialog.Migrate(dialog.currentId, dialog.targetId)
        }
    }

    override fun setListing(listing: EntryBrowseListing) {
        toolbarText.value = ToolbarText.Typed(null)
        model.resetFilters()
        when (listing) {
            EntryBrowseListing.Latest -> model.setListing(NovelBrowseState.Listing.Latest)
            EntryBrowseListing.Popular -> model.setListing(NovelBrowseState.Listing.Popular)
            is EntryBrowseListing.Search -> model.search(listing.query.orEmpty())
        }
    }

    override fun setQuery(query: String?) {
        toolbarText.value = ToolbarText.Typed(query)
    }

    override fun search(query: String?) {
        // A search the screen did not type (a details page handing one over) still has to show, and
        // clearing empties the field, so the toolbar cannot stand open over the listing it went
        // back to. The manga twin gets the second half from `setListing` nulling its toolbar query.
        toolbarText.value = ToolbarText.Typed(query)
        model.search(query.orEmpty())
    }

    override fun setDisplayMode(mode: LibraryDisplayMode) = model.setDisplayMode(mode)

    override fun openFilterSheet() = model.openFilterSheet()

    override fun resetFilters() = model.resetFilters()

    override fun onRowLongClick(row: EntryBrowseRow) = model.onLongClickItem(row.item)

    override fun setSelectionMode(enabled: Boolean) = bulk.toggleSelectionMode(enabled)

    override fun toggleSelection(row: EntryBrowseRow) =
        bulk.toggleSelection(SelectedNovel(sourceId, row.item))

    override fun selectAll(rows: List<EntryBrowseRow>) =
        rows.forEach { bulk.select(SelectedNovel(sourceId, it.item)) }

    override fun invertSelection(rows: List<EntryBrowseRow>) =
        bulk.reverseSelection(rows.map { SelectedNovel(sourceId, it.item) })

    override fun addSelectionToLibrary() = bulk.addFavorite(model.state.value.favoritedKeys)

    override fun setSelectionCategories(categoryIds: List<Long>) {
        val dialog = raisedBulkDialog as? EntryBulkFavoriteViewModel.Dialog.ChangeCategory ?: return
        bulk.setCategories(dialog.items, categoryIds)
    }

    override fun dismissDialog() {
        model.closeFilterSheet()
        model.dismissDialog()
        bulk.setDialog(null)
    }

    override fun confirmRemove() {
        val dialog = raisedDialog as? NovelBrowseDialog.RemoveNovel ?: return
        model.confirmRemove(dialog.item)
    }

    override fun confirmCategories(categoryIds: List<Long>) {
        val dialog = raisedDialog as? NovelBrowseDialog.ChangeCategory ?: return
        model.applyCategories(dialog.target, categoryIds)
    }

    override fun confirmAddDuplicate() {
        val dialog = raisedDialog as? NovelBrowseDialog.AddDuplicate ?: return
        model.addFromDuplicate(dialog.item)
    }

    override fun addToGroup(entryIds: List<Long>) {
        val dialog = raisedDialog as? NovelBrowseDialog.AddDuplicate ?: return
        model.addToExistingGroup(dialog.item, entryIds)
    }

    override fun startMigrate(duplicateId: Long) {
        val dialog = raisedDialog as? NovelBrowseDialog.AddDuplicate ?: return
        model.startMigrate(duplicateId, dialog.item)
    }
}

private fun rowKey(sourceId: String, item: NovelItem) = "novel:$sourceId:${item.path}"

/** The row's payload is this adapter's own result, so unwrapping it is sound only here. */
internal val EntryBrowseRow.item: NovelItem
    get() = content.value.payload as NovelItem

/** Whether the catalogue's search field is still showing the query it was opened with. */
private sealed interface ToolbarText {
    data object Untouched : ToolbarText
    data class Typed(val value: String?) : ToolbarText
}
