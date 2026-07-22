package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import tachiyomi.domain.category.model.Category

/**
 * The neutral, per-content-type library state the shared [LibraryTab][eu.kanade.tachiyomi.ui.library.LibraryTab]
 * renders, so the tab reads one state instead of branching manga-vs-novel for every field. Each adapter
 * (manga over the live `LibraryScreenModel`, novel over `NovelLibraryScreenModel`) maps its own state into
 * this. Only the genuinely per-type content lives here; the shared library display config (view mode,
 * hopper, category-sort order) stays read from the live manga model, since it is one setting for the whole
 * library, not per content type.
 *
 * [itemsForCategory] / [itemCountForCategory] stay functions rather than a precomputed map so the manga
 * side keeps applying its custom-info overlay lazily at the display read (only the visible categories),
 * not eagerly over the whole library on every emission. Each adapter binds them to its own state snapshot.
 */
data class LibraryScreenState(
    val categories: List<Category>,
    val isLoading: Boolean,
    val isLibraryEmpty: Boolean,
    val searchQuery: String?,
    val hasActiveFilters: Boolean,
    /**
     * The selected entries, by neutral identity. Typed rather than raw ids because a manga and a novel
     * can share a row id, so an untyped set could not name a mixed selection unambiguously.
     */
    val selection: Set<EntryId>,
    val selectionMode: Boolean,
    /** Any selected entry is a merge group; drives the bulk Unmerge action. */
    val selectionContainsMerged: Boolean,
    /** The bulk Download action is offered (manga hides it when every selected entry is local). */
    val canDownloadSelection: Boolean,
    val collapsedCategories: Set<String>,
    /** Manga keeps a second collapsed set for dynamic groups; novels reuse [collapsedCategories]. */
    val collapsedDynamicCategories: Set<String>,
    val coercedActiveCategoryIndex: Int,
    /** The resume ("continue reading") button is shown on covers. */
    val showContinueButton: Boolean,
    val itemsForCategory: (Category) -> List<LibraryItem>,
    val itemCountForCategory: (Category) -> Int?,
)
