package reikai.presentation.browse.catalogue

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.map
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import exh.source.getMainSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
) : EntryBrowseBehavior {

    private val mangaDex = model.source.getMainSource<MangaDex>()
        ?.let { MangaDexBrowseCapability(model.source.id) }

    private val capabilities = EntryBrowseCapabilities(mangaDex = mangaDex)

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
                        // that cell alone; this maps it rather than opening a second collector. The
                        // payload stays the live pair, which the gallery rows read the metadata from.
                        content = entryFlow
                            .map { pair -> EntryBrowseRowContent(pair.first.toEntryBrowseUi(), pair) }
                            .stateIn(model.viewModelScope),
                    )
                }
            }
        }
        .stateIn(model.viewModelScope, SharingStarted.WhileSubscribed(), emptyFlow())

    private fun toNeutral(
        state: BrowseSourceViewModel.State,
        bulkState: EntryBulkFavoriteViewModel.State<Manga>,
    ): EntryBrowseScreenState {
        val source = model.source
        if (source is StubSource) return EntryBrowseScreenState.SourceMissing(source.toString())
        return EntryBrowseScreenState.Loaded(
            sourceName = source.name,
            listing = state.listing.toNeutral(),
            query = state.toolbarQuery,
            isUserQuery = state.isUserQuery,
            supportsLatest = source.supportsLatest,
            hasFilters = state.filters.isNotEmpty(),
            filtersActive = state.listing is Listing.Search,
            hasSettings = source is ConfigurableSource,
            rowStyle = if (model.useEhentaiView) {
                EntryBrowseRowStyle.Gallery
            } else {
                EntryBrowseRowStyle.Standard(model.displayMode)
            },
            selectionMode = bulkState.selectionMode,
            selectedKeys = bulkState.selection.mapTo(mutableSetOf(), ::mangaRowKey),
            capabilities = capabilities,
            dialog = state.dialog?.toNeutral(),
        )
    }

    private fun Listing.toNeutral(): EntryBrowseListing = when (this) {
        Listing.Popular -> EntryBrowseListing.Popular
        Listing.Latest -> EntryBrowseListing.Latest
        is Listing.Search -> EntryBrowseListing.Search(query)
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

    override fun search(query: String?) = model.search(query)

    override fun setDisplayMode(mode: LibraryDisplayMode) {
        model.displayMode = mode
    }

    override fun openFilterSheet() = model.openFilterSheet()

    override fun resetFilters() = model.resetFilters()

    override fun onRowLongClick(row: EntryBrowseRow) = model.onLongClick(row.manga)

    override fun setSelectionMode(enabled: Boolean) = bulk.toggleSelectionMode(enabled)

    override fun toggleSelection(row: EntryBrowseRow) = bulk.toggleSelection(row.manga)

    override fun selectAll(rows: List<EntryBrowseRow>) = rows.forEach { bulk.select(it.manga) }

    override fun invertSelection(rows: List<EntryBrowseRow>) =
        bulk.reverseSelection(rows.map { it.manga })

    override fun addSelectionToLibrary() = bulk.addFavorite()

    override fun setSelectionCategories(categoryIds: List<Long>) {
        val dialog = bulk.state.value.dialog as? EntryBulkFavoriteViewModel.Dialog.ChangeCategory
            ?: return
        bulk.setCategories(dialog.items, categoryIds)
    }

    override fun dismissDialog() = model.setDialog(null)

    override fun confirmRemove() {
        val dialog = model.state.value.dialog as? BrowseSourceViewModel.Dialog.RemoveManga ?: return
        model.changeMangaFavorite(dialog.manga)
    }

    override fun confirmCategories(categoryIds: List<Long>) {
        val dialog = model.state.value.dialog as? BrowseSourceViewModel.Dialog.ChangeMangaCategory
            ?: return
        model.confirmCategories(dialog.manga, categoryIds, dialog.alreadyFavorited)
    }

    override fun confirmAddDuplicate() {
        val dialog = model.state.value.dialog as? BrowseSourceViewModel.Dialog.AddDuplicateManga
            ?: return
        model.addFavorite(dialog.manga)
    }

    override fun addToGroup(entryIds: List<Long>) {
        val dialog = model.state.value.dialog as? BrowseSourceViewModel.Dialog.AddDuplicateManga
            ?: return
        model.addToExistingGroup(dialog.manga, entryIds)
    }

    override fun startMigrate(duplicateId: Long) {
        val dialog = model.state.value.dialog as? BrowseSourceViewModel.Dialog.AddDuplicateManga
            ?: return
        val target = dialog.duplicates.firstOrNull { it.manga.id == duplicateId }?.manga ?: return
        model.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, target))
    }
}

internal fun mangaRowKey(manga: Manga) = "manga:${manga.id}"

/** The row's payload is this adapter's own entry pair, so unwrapping it is sound only here. */
internal val EntryBrowseRow.manga: Manga
    get() = (content.value.payload as Pair<*, *>).first as Manga
