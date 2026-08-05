package reikai.presentation.library

import reikai.domain.entry.EntryId

/**
 * The neutral, per-content-type library state the shared LibraryTab renders, so the tab reads one state
 * instead of branching manga-vs-novel for every field. Each adapter maps its own model's state into
 * this. Only genuinely per-type content lives here; anything library-wide (the display config, which
 * categories are collapsed) belongs to [LibraryEngine], because the chips filter one list rather than
 * selecting between two. The list itself is NOT here: categories, rows and counts come off
 * [LibraryEngine.assembled], the only thing that can bucket both content types into one list.
 */
data class LibraryScreenState(
    val isLoading: Boolean,
    val isLibraryEmpty: Boolean,
    val searchQuery: String?,
    val hasActiveFilters: Boolean,
    /**
     * The raw page index, uncoerced. Each model coerces against its own category list, which under the
     * All chip is not the list on screen, so the tab coerces against what it actually renders.
     */
    val activeCategoryIndex: Int,
    /** The resume ("continue reading") button is shown on covers. */
    val showContinueButton: Boolean,
    /**
     * Identity of the custom-info map this state was built from. Nothing reads it: it exists so a
     * custom-title or custom-cover edit changes this state's equality. The rows deliberately exclude the
     * overlay (it is applied at the display read), so without this field a customInfo-only edit leaves
     * every other field equal, the state flow conflates it away, and the edit never reaches the screen.
     */
    val overlayKey: Any?,
)
