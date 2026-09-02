package reikai.presentation.browse.catalogue

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.map
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.source.filter.MangaSavedSearchFilters
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.toEntryBrowseUi
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource

/**
 * Adapts the live [BrowseSourceViewModel] plus its bulk-selection model to the neutral
 * [EntryBrowseBehavior]. Mihon's model stays upstream-tracked and never implements a Reikai
 * interface, so every shape mismatch reconciles HERE. Symmetric with [NovelBrowseAdapter], so one
 * shared browse screen drives both catalogues; manga-only affordances go through capability slots
 * rather than onto the shared spine.
 */
class MangaBrowseAdapter(
    private val model: BrowseSourceViewModel,
    private val bulk: BulkFavoriteViewModel,
    migrateForId: Long? = null,
    /** Report the picked target back, which the screen turns into a handoff and a pop. */
    onPickTarget: (targetId: Long) -> Unit = {},
) : EntryBrowseBehavior {

    /**
     * The dialog each verb acts on, kept as it is mapped rather than read back off the model.
     *
     * The shared dialogs dismiss before they call back, and dismissing clears the model's own
     * dialog, so a verb reading it there finds nothing and silently does nothing. Never stale: a
     * dialog cannot reach the screen without being mapped here first.
     */
    @Volatile private var raisedDialog: BrowseSourceViewModel.Dialog? = null

    @Volatile private var raisedBulkDialog: EntryBulkFavoriteViewModel.Dialog<Manga>? = null

    private val savedSearchFilters = MangaSavedSearchFilters()

    private val capabilities = EntryBrowseCapabilities(
        migrationPick = migrateForId?.let { id ->
            // A browsed manga row is already a stored row, so unlike the novel twin there is
            // nothing to materialize before the pick can name it.
            MigrationPickCapability(id) { row, onPicked ->
                onPickTarget(row.manga.id)
                onPicked()
            }
        },
    )

    override val state: StateFlow<EntryBrowseScreenState> =
        combine(model.state, bulk.state, ::toNeutral)
            // WhileSubscribed, not Eagerly: the adapter is rebuilt per composition entry while its
            // sharing coroutine lives in the model's scope, so an eager start leaves one orphaned
            // mapper running per re-entry.
            .stateIn(
                model.viewModelScope,
                SharingStarted.WhileSubscribed(),
                toNeutral(model.state.value, bulk.state.value),
            )

    override val rows: StateFlow<Flow<PagingData<EntryBrowseRow>>> = model.mangaPagerFlowFlow
        .map { pagerFlow ->
            pagerFlow.map { pagingData ->
                pagingData.map { entryFlow ->
                    EntryBrowseRow(
                        key = mangaRowKey(entryFlow.value.first),
                        // The model already keeps one flow per entry so a favourite toggle re-renders
                        // that cell alone; this is a view of it rather than a second collector. The
                        // payload stays the live pair, which the gallery rows read the metadata from.
                        content = entryFlow.mapState { pair ->
                            EntryBrowseRowContent(pair.first.toEntryBrowseUi(), pair)
                        },
                    )
                }
            }
        }
        .stateIn(model.viewModelScope, SharingStarted.WhileSubscribed(), emptyFlow())

    private fun toNeutral(
        state: BrowseSourceViewModel.State,
        bulkState: EntryBulkFavoriteViewModel.State<Manga>,
    ): EntryBrowseScreenState {
        state.dialog?.let { raisedDialog = it }
        bulkState.dialog?.let { raisedBulkDialog = it }
        val source = model.source
        if (source is StubSource) return EntryBrowseScreenState.SourceMissing(source.toString())
        return EntryBrowseScreenState.Loaded(
            sourceName = source.name,
            listing = state.listing.toNeutral(),
            query = state.toolbarQuery,
            isUserQuery = state.isUserQuery,
            supportsLatest = source.supportsLatest,
            hasFilters = state.filters.isNotEmpty(),
            filtersActive = state.filterChipActive(),
            hasSettings = source is ConfigurableSource,
            webUrl = (source as? HttpSource)?.getHomeUrl(),
            rowStyle = if (model.useEhentaiView) {
                EntryBrowseRowStyle.Gallery
            } else {
                EntryBrowseRowStyle.Standard(state.displayMode)
            },
            selectionMode = bulkState.selectionMode,
            selectedKeys = bulkState.selection.mapTo(mutableSetOf(), ::mangaRowKey),
            capabilities = capabilities,
            // One dialog channel: the bulk category picker only ever opens while an entry dialog is
            // closed, so it rides the same slot rather than needing a second one in the state.
            dialog = state.dialog?.toNeutral() ?: bulkState.dialog?.toNeutral(),
        )
    }

    private fun Listing.toNeutral(): EntryBrowseListing = when (this) {
        Listing.Popular -> EntryBrowseListing.Popular
        Listing.Latest -> EntryBrowseListing.Latest
        is Listing.Search -> EntryBrowseListing.Search(query)
    }

    private fun EntryBulkFavoriteViewModel.Dialog<Manga>.toNeutral(): EntryBrowseDialog = when (this) {
        is EntryBulkFavoriteViewModel.Dialog.ChangeCategory ->
            EntryBrowseDialog.SelectionCategories(initialSelection)
    }

    private fun BrowseSourceViewModel.Dialog.toNeutral(): EntryBrowseDialog = when (this) {
        BrowseSourceViewModel.Dialog.Filter -> EntryBrowseDialog.Filter
        is BrowseSourceViewModel.Dialog.RemoveManga -> EntryBrowseDialog.Remove(manga.title)
        is BrowseSourceViewModel.Dialog.ChangeMangaCategory ->
            EntryBrowseDialog.ChangeCategory(initialSelection)
        is BrowseSourceViewModel.Dialog.AddDuplicateManga -> EntryBrowseDialog.AddDuplicate(
            duplicates = duplicates.map { it.toDuplicateCard(sourceLabels) },
            groupIdByEntryId = groupIdByMangaId,
            suggestGroup = suggestGroup,
        )
        is BrowseSourceViewModel.Dialog.Migrate -> EntryBrowseDialog.Migrate(current.id, target.id)
    }

    override fun setListing(listing: EntryBrowseListing) {
        model.resetFilters()
        model.setListing(
            when (listing) {
                EntryBrowseListing.Popular -> Listing.Popular
                EntryBrowseListing.Latest -> Listing.Latest
                is EntryBrowseListing.Search -> Listing.valueOf(listing.query)
            },
        )
    }

    override fun setQuery(query: String?) = model.setToolbarQuery(query)

    override fun search(query: String?) {
        // Clearing means the listing back, per the contract. Upstream's own `search(null)` keeps the
        // query it already had, which would leave an empty field standing over its results.
        if (query.isNullOrBlank()) setListing(EntryBrowseListing.Popular) else model.search(query)
    }

    override fun setDisplayMode(mode: LibraryDisplayMode) = model.setDisplayMode(mode)

    override fun openFilterSheet() = model.openFilterSheet()

    override fun resetFilters() = model.resetFilters()

    override fun captureSearch(): SavedSearchDraft {
        val state = model.state.value
        return SavedSearchDraft(
            query = (state.listing as? Listing.Search)?.query?.takeIf { it.isNotBlank() },
            filtersJson = savedSearchFilters.encode(state.filters),
        )
    }

    override fun applySearch(query: String?, filtersJson: String?) {
        // Onto a list the source builds now rather than the one on screen, so a saved search reads the
        // source's current defaults for anything it does not carry a value for.
        val filters = model.source.getFilterList()
        filtersJson?.let { savedSearchFilters.decode(it, filters) }
        // Empty rather than null for a search that carries none: Mihon's search() reads null as "keep
        // what is there", so a filters-only search would otherwise run against whatever the reader had
        // typed and show results the saved search never described. The novel half already clears it.
        model.search(query = query.orEmpty(), filters = filters)
    }

    override fun onRowLongClick(row: EntryBrowseRow) = model.onLongClick(row.manga)

    override fun setSelectionMode(enabled: Boolean) = bulk.toggleSelectionMode(enabled)

    override fun toggleSelection(row: EntryBrowseRow) = bulk.toggleSelection(row.manga)

    override fun selectAll(rows: List<EntryBrowseRow>) = rows.forEach { bulk.select(it.manga) }

    override fun invertSelection(rows: List<EntryBrowseRow>) =
        bulk.reverseSelection(rows.map { it.manga })

    override fun addSelectionToLibrary() = bulk.addFavorite()

    override fun setSelectionCategories(categoryIds: List<Long>) {
        val dialog = raisedBulkDialog as? EntryBulkFavoriteViewModel.Dialog.ChangeCategory ?: return
        bulk.setCategories(dialog.items, categoryIds)
    }

    override fun dismissDialog() {
        model.setDialog(null)
        bulk.setDialog(null)
    }

    override fun confirmRemove() {
        val dialog = raisedDialog as? BrowseSourceViewModel.Dialog.RemoveManga ?: return
        model.changeMangaFavorite(dialog.manga)
    }

    override fun confirmCategories(categoryIds: List<Long>) {
        val dialog = raisedDialog as? BrowseSourceViewModel.Dialog.ChangeMangaCategory ?: return
        model.confirmCategories(dialog.manga, categoryIds, dialog.alreadyFavorited)
    }

    override fun confirmAddDuplicate() {
        val dialog = raisedDialog as? BrowseSourceViewModel.Dialog.AddDuplicateManga ?: return
        model.addFavorite(dialog.manga)
    }

    override fun addToGroup(entryIds: List<Long>) {
        val dialog = raisedDialog as? BrowseSourceViewModel.Dialog.AddDuplicateManga ?: return
        model.addToExistingGroup(dialog.manga, entryIds)
    }

    override fun startMigrate(duplicateId: Long) {
        val dialog = raisedDialog as? BrowseSourceViewModel.Dialog.AddDuplicateManga ?: return
        val target = dialog.duplicates.firstOrNull { it.manga.id == duplicateId }?.manga ?: return
        model.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, target))
    }
}

/**
 * Whether the Filter chip reads as active. Upstream's rule: any search-shaped listing, so a
 * plain text search lights it and so does a reset that was re-applied. The novel twin answers
 * the same question off its own state; FilterChipConformanceTest runs both.
 */
internal fun BrowseSourceViewModel.State.filterChipActive(): Boolean = listing is Listing.Search

internal fun mangaRowKey(manga: Manga) = "manga:${manga.id}"

/** The row's payload is this adapter's own entry pair, so unwrapping it is sound only here. */
internal val EntryBrowseRow.manga: Manga
    get() = (content.value.payload as Pair<*, *>).first as Manga
