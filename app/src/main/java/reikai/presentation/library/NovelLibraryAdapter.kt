package reikai.presentation.library

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.library.novels.NovelLibraryScreenModel
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga

/**
 * Adapts the Reikai [NovelLibraryScreenModel] to the neutral [LibraryBehavior], the novel twin of
 * [MangaLibraryAdapter]. Maps the novel state into [LibraryScreenState] and reconciles the per-type action
 * shapes here (a `(Category, LibraryManga)` selection call fans back out to the novel model's `(categoryId,
 * id)` form; the manga side's split default / dynamic collapse toggles both route to the novel model's
 * single one), never in the model.
 */
class NovelLibraryAdapter(
    private val model: NovelLibraryScreenModel,
) : LibraryProvider {

    override val contentType = ContentType.NOVELS

    override val state: StateFlow<LibraryScreenState> =
        model.state
            .map { it.toNeutral() }
            .stateIn(model.screenModelScope, SharingStarted.Eagerly, model.state.value.toNeutral())

    private fun NovelLibraryScreenModel.State.toNeutral() = LibraryScreenState(
        categories = displayedCategories,
        isLoading = isLoading,
        isLibraryEmpty = isLibraryEmpty,
        searchQuery = searchQuery,
        hasActiveFilters = hasActiveFilters,
        collapsedCategories = collapsedCategories,
        // Novels keep one collapsed set for both real and dynamic categories.
        collapsedDynamicCategories = collapsedCategories,
        coercedActiveCategoryIndex = coercedActiveCategoryIndex,
        showContinueButton = showContinueButton,
        itemsForCategory = this::getItemsForCategory,
        itemCountForCategory = this::getItemCountForCategory,
    )

    override fun search(query: String?) {
        model.search(query)
    }
    override fun toggleDefaultCategoryCollapse(headerKey: String) {
        model.toggleCategoryCollapse(headerKey)
    }
    override fun toggleDynamicCategoryCollapse(headerKey: String) {
        model.toggleCategoryCollapse(headerKey)
    }
    override fun toggleAllCategoriesCollapsed(
        categories: List<Category>,
    ) {
        model.toggleAllCategoriesCollapsed(categories)
    }

    // Each verb takes the neutral selection and hands the model only the raw ids of its own content
    // type, so a mixed selection never reaches a provider that cannot act on it.
    private fun Set<EntryId>.ownIds() = filterIsInstance<EntryId.Novel>().map { it.rawId }

    override fun markReadSelection(entries: Set<EntryId>, read: Boolean) {
        model.markReadSelection(entries.ownIds(), read)
    }
    override fun performDownloadAction(entries: Set<EntryId>, action: DownloadAction) {
        model.performDownloadAction(entries.ownIds(), action)
    }
    override fun openChangeCategoryDialog(entries: Set<EntryId>) {
        model.openChangeCategoryDialog(entries.ownIds())
    }
    override fun openDeleteDialog(entries: Set<EntryId>) {
        model.openDeleteDialog(entries.ownIds())
    }
    override fun mergeSelection(entries: Set<EntryId>) {
        model.mergeSelection(entries.ownIds())
    }
    override fun unmergeSelection(entries: Set<EntryId>) {
        model.unmergeSelection(entries.ownIds())
    }
    override fun containsMerged(entries: Set<EntryId>) =
        model.state.value.containsMerged(entries.ownIds())

    /** Novels have no local-source concept, so Download always applies. */
    override fun canDownload(entries: Set<EntryId>) = true
    override fun updateActiveCategoryIndex(index: Int) {
        model.updateActiveCategoryIndex(index)
    }
    override fun openSettingsDialog(categoryId: Long?, initialTab: Int) {
        model.openSettingsDialog(categoryId ?: Category.UNCATEGORIZED_ID, initialTab)
    }
}
