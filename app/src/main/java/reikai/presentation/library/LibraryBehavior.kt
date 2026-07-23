package reikai.presentation.library

import eu.kanade.presentation.manga.DownloadAction
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.entry.EntryId
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga

/**
 * The neutral action set the shared library tab dispatches, so a selection / filter / category action is
 * written once against this seam instead of an `if (isNovels)` per call. Each adapter maps a call onto its
 * own model. Per-type navigation (opening an entry, the reader, migration) and per-type dialog rendering
 * stay in the tab, matching the details surface: they need the Voyager navigator and per-type screen types,
 * so the shared spine never rots into no-op methods.
 *
 * Selection actions key on `(Category, LibraryManga)` because novels are disguised as `LibraryManga` at the
 * leaf, so the grid hands the same type for both; the novel adapter extracts the ids its model wants.
 */
interface LibraryBehavior {
    val state: StateFlow<LibraryScreenState>

    fun search(query: String?)

    // Selection.
    fun toggleSelection(category: Category, item: LibraryManga)
    fun toggleRangeSelection(category: Category, item: LibraryManga)
    fun selectAll()
    fun invertSelection()
    fun clearSelection()
    fun selectAllInCategory(category: Category)

    // Category collapse. Manga has separate default / dynamic toggles; the novel model folds both into one.
    fun toggleDefaultCategoryCollapse(headerKey: String)
    fun toggleDynamicCategoryCollapse(headerKey: String)
    fun toggleAllCategoriesCollapsed(categories: List<Category>)

    // Bulk selection actions. Each takes the entries to act on rather than reading a selection the
    // provider owns, so the shared layer can hold one selection spanning both content types and hand
    // each provider only its own ids. openChangeCategoryDialog / openDeleteDialog open a per-type dialog
    // that the tab renders; the behaviour only triggers it.
    fun markReadSelection(entries: Set<EntryId>, read: Boolean)
    fun performDownloadAction(entries: Set<EntryId>, action: DownloadAction)
    fun openChangeCategoryDialog(entries: Set<EntryId>)
    fun openDeleteDialog(entries: Set<EntryId>)
    fun mergeSelection(entries: Set<EntryId>)
    fun unmergeSelection(entries: Set<EntryId>)

    // Questions about a set of entries, asked the same way the verbs are told what to act on, so the
    // answers never depend on a selection the provider holds privately.

    /** Any of [entries] is a merge group; drives the bulk Unmerge action. */
    fun containsMerged(entries: Set<EntryId>): Boolean

    /** The bulk Download action applies (manga hides it when every selected entry is local). */
    fun canDownload(entries: Set<EntryId>): Boolean

    // Paging + the settings sheet. A null categoryId is the global-sort scope; the novel adapter maps it to
    // its uncategorized sentinel.
    fun updateActiveCategoryIndex(index: Int)
    fun openSettingsDialog(categoryId: Long?, initialTab: Int)
}
