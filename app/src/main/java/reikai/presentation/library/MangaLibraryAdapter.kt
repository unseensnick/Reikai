package reikai.presentation.library

import androidx.compose.ui.util.fastAll
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.ui.library.LibraryScreenModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.source.local.isLocal

/**
 * Adapts the live Mihon [LibraryScreenModel] to the neutral [LibraryBehavior]. The model stays live and
 * upstream-tracked (never made to implement a Reikai interface); this maps its state into the neutral
 * [LibraryScreenState] and forwards each neutral action to the model's own methods. Symmetric with
 * [NovelLibraryAdapter], so one shared library tab drives both content types through this seam.
 */
class MangaLibraryAdapter(
    private val model: LibraryScreenModel,
) : LibraryProvider {

    override val contentType = ContentType.MANGA

    override val state: StateFlow<LibraryScreenState> =
        model.state
            .map { it.toNeutral() }
            .stateIn(model.screenModelScope, SharingStarted.Eagerly, model.state.value.toNeutral())

    private fun LibraryScreenModel.State.toNeutral() = LibraryScreenState(
        categories = displayedCategories,
        isLoading = isLoading,
        isLibraryEmpty = isLibraryEmpty,
        searchQuery = searchQuery,
        hasActiveFilters = hasActiveFilters,
        // The model keeps upstream's raw-id selection; the content type is stamped on here, since an
        // adapter always knows its own.
        selection = selection.mapTo(mutableSetOf()) { EntryId.Manga(it) },
        selectionMode = selectionMode,
        selectionContainsMerged = selectionContainsMerged,
        canDownloadSelection = selectedManga.fastAll { !it.isLocal() },
        collapsedCategories = reikai.collapsedCategories,
        collapsedDynamicCategories = reikai.collapsedDynamicCategories,
        coercedActiveCategoryIndex = coercedActiveCategoryIndex,
        showContinueButton = showMangaContinueButton,
        itemsForCategory = this::getItemsForCategory,
        itemCountForCategory = this::getItemCountForCategory,
    )

    override fun search(query: String?) {
        model.search(query)
    }
    override fun toggleSelection(category: Category, item: LibraryManga) {
        model.toggleSelection(category, item)
    }
    override fun toggleRangeSelection(
        category: Category,
        item: LibraryManga,
    ) {
        model.toggleRangeSelection(category, item)
    }
    override fun selectAll() {
        model.selectAll()
    }
    override fun invertSelection() {
        model.invertSelection()
    }
    override fun clearSelection() {
        model.clearSelection()
    }
    override fun selectAllInCategory(category: Category) {
        model.selectAllInCategory(category)
    }
    override fun toggleDefaultCategoryCollapse(headerKey: String) {
        model.toggleDefaultCategoryCollapse(headerKey)
    }
    override fun toggleDynamicCategoryCollapse(headerKey: String) {
        model.toggleDynamicCategoryCollapse(headerKey)
    }
    override fun toggleAllCategoriesCollapsed(
        categories: List<Category>,
    ) {
        model.toggleAllCategoriesCollapsed(categories)
    }
    override fun markReadSelection(read: Boolean) {
        model.markReadSelection(read)
    }
    override fun performDownloadAction(action: DownloadAction) {
        model.performDownloadAction(action)
    }
    override fun openChangeCategoryDialog() {
        model.openChangeCategoryDialog()
    }
    override fun openDeleteDialog() {
        model.openDeleteMangaDialog()
    }
    override fun mergeSelection() {
        model.mergeSelection()
    }
    override fun unmergeSelection() {
        model.unmergeSelection()
    }
    override fun updateActiveCategoryIndex(index: Int) {
        model.updateActiveCategoryIndex(index)
    }
    override fun openSettingsDialog(
        categoryId: Long?,
        initialTab: Int,
    ) {
        model.showSettingsDialog(initialTab, categoryId)
    }
}
