package reikai.presentation.browse.catalogue

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** The in-toolbar text, which the model has no field for: its own `query` is the committed one. */
    private val toolbarQuery = MutableStateFlow<String?>(null)

    override val state: StateFlow<EntryBrowseScreenState> =
        combine(model.state, bulk.state, toolbarQuery, ::toNeutral)
            // WhileSubscribed, not Eagerly: the adapter is rebuilt per composition entry while its
            // sharing coroutine lives in the model's scope, so an eager start leaves one orphaned
            // mapper running per re-entry.
            .stateIn(
                model.viewModelScope,
                SharingStarted.WhileSubscribed(),
                toNeutral(model.state.value, bulk.state.value, toolbarQuery.value),
            )

    override val rows: StateFlow<Flow<PagingData<EntryBrowseRow>>> = model.novelPagerFlowFlow
        .map { pagerFlow ->
            pagerFlow.map { pagingData ->
                pagingData.map { item ->
                    EntryBrowseRow(
                        key = rowKey(sourceId, item),
                        // A browse result carries no library state of its own, so the cell reads it
                        // off the model, which is what re-renders the badge when the entry is added.
                        content = model.state
                            .map { it.rowContent(item) }
                            .distinctUntilChanged()
                            .stateIn(model.viewModelScope),
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
        query: String?,
    ): EntryBrowseScreenState {
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
            query = query,
            isUserQuery = searching,
            // The plugin format declares no latest flag, so every plugin is offered the chip until
            // the installer can answer for it from the plugin's own source text.
            supportsLatest = true,
            hasFilters = source.filters?.isNotEmpty() == true,
            filtersActive = searching || state.hasActiveFilters,
            hasSettings = source.pluginSettings != null,
            rowStyle = EntryBrowseRowStyle.Standard(model.displayMode),
            selectionMode = bulkState.selectionMode,
            selectedKeys = bulkState.selection.mapTo(mutableSetOf()) { rowKey(it.sourceId, it.item) },
            capabilities = capabilities,
            dialog = state.toNeutralDialog(),
        )
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
        toolbarQuery.value = null
        model.resetFilters()
        when (listing) {
            EntryBrowseListing.Latest -> model.setListing(NovelBrowseState.Listing.Latest)
            EntryBrowseListing.Popular -> model.setListing(NovelBrowseState.Listing.Popular)
            is EntryBrowseListing.Search -> model.search(listing.query.orEmpty())
        }
    }

    override fun setQuery(query: String?) {
        toolbarQuery.value = query
    }

    override fun search(query: String?) = model.search(query.orEmpty())

    override fun setDisplayMode(mode: LibraryDisplayMode) {
        model.displayMode = mode
    }

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
        val dialog = bulk.state.value.dialog as? EntryBulkFavoriteViewModel.Dialog.ChangeCategory
            ?: return
        bulk.setCategories(dialog.items, categoryIds)
    }

    override fun dismissDialog() {
        model.closeFilterSheet()
        model.dismissDialog()
    }

    override fun confirmRemove() {
        val dialog = model.state.value.dialog as? NovelBrowseDialog.RemoveNovel ?: return
        model.confirmRemove(dialog.item)
    }

    override fun confirmCategories(categoryIds: List<Long>) {
        val dialog = model.state.value.dialog as? NovelBrowseDialog.ChangeCategory ?: return
        model.applyCategories(dialog.target, categoryIds)
    }

    override fun confirmAddDuplicate() {
        val dialog = model.state.value.dialog as? NovelBrowseDialog.AddDuplicate ?: return
        model.addFromDuplicate(dialog.item)
    }

    override fun addToGroup(entryIds: List<Long>) {
        val dialog = model.state.value.dialog as? NovelBrowseDialog.AddDuplicate ?: return
        model.addToExistingGroup(dialog.item, entryIds)
    }

    override fun startMigrate(duplicateId: Long) {
        val dialog = model.state.value.dialog as? NovelBrowseDialog.AddDuplicate ?: return
        model.startMigrate(duplicateId, dialog.item)
    }
}

private fun rowKey(sourceId: String, item: NovelItem) = "novel:$sourceId:${item.path}"

/** The row's payload is this adapter's own result, so unwrapping it is sound only here. */
internal val EntryBrowseRow.item: NovelItem
    get() = content.value.payload as NovelItem
