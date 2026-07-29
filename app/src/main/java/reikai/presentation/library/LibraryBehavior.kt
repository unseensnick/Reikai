package reikai.presentation.library

import eu.kanade.presentation.manga.DownloadAction
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.entry.EntryId
import tachiyomi.domain.category.model.Category

/**
 * The neutral action set the shared library tab dispatches, so a selection / filter / category action is
 * written once against this seam instead of an `if (isNovels)` per call. Each adapter maps a call onto its
 * own model. Per-type navigation (opening an entry, the reader, migration) stays in the tab, matching the
 * details surface: it needs the Voyager navigator and per-type screen types, so the shared spine never rots
 * into no-op methods.
 *
 * Dialogs are not opened here. A provider only answers questions about entries and performs the confirmed
 * write; [LibraryEngine] builds the dialog, because anything derived from the selection has to be derived
 * where the selection lives.
 */
interface LibraryBehavior {
    val state: StateFlow<LibraryScreenState>

    fun search(query: String?)

    // Selection itself lives in LibraryEngine, not here: a combined list can hold both content types at
    // once and a range-select can span them, which neither provider can compute alone.

    // Category collapse. Manga has separate default / dynamic toggles; the novel model folds both into one.
    fun toggleDefaultCategoryCollapse(headerKey: String)
    fun toggleDynamicCategoryCollapse(headerKey: String)
    fun toggleAllCategoriesCollapsed(categories: List<Category>)

    // Bulk selection actions. Each takes the entries to act on rather than reading a selection the
    // provider owns, so the shared layer can hold one selection spanning both content types and hand
    // each provider only its own ids.
    fun markReadSelection(entries: Set<EntryId>, read: Boolean)
    fun performDownloadAction(entries: Set<EntryId>, action: DownloadAction)
    fun mergeSelection(entries: Set<EntryId>)
    fun unmergeSelection(entries: Set<EntryId>)

    /** Assign [entries] to categories. Merge-group members are expanded by the provider. */
    fun setCategories(entries: Set<EntryId>, addCategories: List<Long>, removeCategories: List<Long>)

    /** Remove [entries] from the library and/or delete their downloads. */
    fun deleteEntries(
        entries: Set<EntryId>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        removeGroupedSources: Boolean,
    )

    // Questions about a set of entries, asked the same way the verbs are told what to act on, so the
    // answers never depend on a selection the provider holds privately.

    /** Any of [entries] is a merge group; drives the bulk Unmerge action. */
    fun containsMerged(entries: Set<EntryId>): Boolean

    /** The bulk Download action applies (manga hides it when every selected entry is local). */
    fun canDownload(entries: Set<EntryId>): Boolean

    /** Grouped sources behind [entries] (0 = nothing merged), for the delete dialog's opt-in checkbox. */
    fun groupedSourceCount(entries: Set<EntryId>): Int

    /** Any of [entries] comes from a local source, which has no downloads to delete. */
    fun containsLocal(entries: Set<EntryId>): Boolean

    /**
     * Every category this content type can be assigned to, system rows excluded.
     *
     * A fresh read, never the displayed list: that one is narrowed by the hidden-category toggle and by an
     * active search, which once made a hidden category impossible to assign to because its checkbox never
     * appeared. An empty category is hidden from the grid and must still be pickable here.
     */
    suspend fun assignableCategories(): List<Category>

    /**
     * The category ids held by each of [entries], one set per entity, expanded to every merge-group
     * member. Feeds the change-categories tri-state, so a category only some members hold reads as mixed
     * instead of being silently dropped by the write that follows.
     */
    suspend fun categoryIdsFor(entries: Set<EntryId>): List<Set<Long>>

    fun updateActiveCategoryIndex(index: Int)
}
