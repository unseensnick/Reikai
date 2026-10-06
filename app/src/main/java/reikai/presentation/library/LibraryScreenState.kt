package reikai.presentation.library

import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

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
    /** The resume ("continue reading") button is shown on covers. */
    val showContinueButton: Boolean,
    /**
     * Identity of the custom-info map this state was built from. Nothing reads it: it exists so a
     * custom-title or custom-cover edit changes this state's equality. The rows deliberately exclude the
     * overlay (it is applied at the display read), so without this field a customInfo-only edit leaves
     * every other field equal, the state flow conflates it away, and the edit never reaches the screen.
     */
    val overlayKey: Any?,
) {
    companion object {
        /**
         * The state the [chip] shows. All combines the two types: loading or filtered while either is,
         * empty only when both are, and carrying both overlay keys so either type's edit reaches the screen.
         * The search and the continue-reading setting are shared, so the manga side answers for both.
         */
        fun forChip(chip: ContentType, manga: LibraryScreenState, novel: LibraryScreenState): LibraryScreenState =
            when (chip) {
                ContentType.MANGA -> manga
                ContentType.NOVELS -> novel
                ContentType.ALL -> LibraryScreenState(
                    isLoading = manga.isLoading || novel.isLoading,
                    isLibraryEmpty = manga.isLibraryEmpty && novel.isLibraryEmpty,
                    searchQuery = manga.searchQuery,
                    hasActiveFilters = manga.hasActiveFilters || novel.hasActiveFilters,
                    showContinueButton = manga.showContinueButton,
                    overlayKey = manga.overlayKey to novel.overlayKey,
                )
            }
    }
}
