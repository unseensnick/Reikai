package yokai.presentation.library

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.database.models.ILibraryCategory
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Tab-agnostic library state. Each tab (Manga, Novels) has its own screen model that emits this
 * type, parameterized by the variant of [LibraryItem] it renders.
 *
 * Covariant in [T] so the shared composable can accept `LibraryTabState<out LibraryItem>` and
 * render either tab's emission.
 */
@Immutable
sealed interface LibraryTabState<out T : LibraryItem, out C : ILibraryCategory> {
    data object Loading : LibraryTabState<Nothing, Nothing>

    data class Loaded<T : LibraryItem, C : ILibraryCategory>(
        val library: Map<C, List<T>>,
        val totalItemCount: Int,
        /** Whether `LibraryUpdateJob` is currently running. */
        val isRunning: Boolean = false,
        /**
         * Category IDs whose updates are queued (mid-update or pending) for the running job.
         * Re-derived per `isRunningFlow` tick by walking loaded categories and calling
         * `LibraryUpdater.isCategoryInQueue`, mirroring `LibraryController.kt:621-625`.
         */
        val inQueueCategoryIds: Set<Int> = emptySet(),
        /**
         * Order (not id) of the category the user is currently focused on, backed by
         * `preferences.lastUsedCategory()`. The Compose port keeps this in sync with the
         * visible category via a scroll-anchored write in `LibraryContent`. Used as the
         * pull-to-refresh target when `showAllCategories = false`, matching legacy
         * `presenter.currentCategory` semantics.
         */
        val currentCategoryOrder: Int = 0,
        /**
         * Manga ids that are currently selected in multi-select action mode. Empty when the
         * action bar is not active. Carried forward across reload emissions so a download-cache
         * tick or library update does not drop the user's selection mid-action.
         */
        val selection: Set<Long> = emptySet(),
        /**
         * Bumped by the screen model on every optimistic sort update so StateFlow's
         * distinct-until-changed never elides a sort emission. Necessary because
         * `CategoryImpl.equals` compares by `name` (not reference), so a cloned Category with
         * the same name reads as equal — and if the re-sorted item list happens to produce the
         * same order as before (single-item category, coincidentally identical ordering), the
         * Loaded data class would compare equal and the UI would never recompose.
         *
         * Carried forward unchanged on emissions that originate from the reactive combine; only
         * optimistic sort writes bump it.
         */
        val sortEpoch: Int = 0,
        /**
         * Tracked so the state varies when the Reikai-fork `preferences.categorySortOrder`
         * pref changes. `Map<Category, ...>.equals` ignores iteration order (it compares
         * key-value pairs unordered) and `CategoryImpl.equals` is name-based, so a sort-order
         * change alone produces a library map that compares equal to the previous one — the
         * UI would never re-render the new category sequence. Including this here forces the
         * Loaded equality check to detect the change.
         */
        val categorySortOrder: Int = 0,
        /**
         * Tracked so the state varies when `preferences.collapsedDynamicCategories` changes.
         * Without this, toggling a dynamic header's chevron rebuilds the library map with new
         * synthetic Category objects (different `isHidden`), but `CategoryImpl.equals` is
         * name-based and `Map.equals` is content-only — the new map compares equal to the
         * old, the Loaded data class compares equal, and `MutableState.value`'s
         * structural-equivalent setter elides the emission. The UI then never sees the new
         * collapsed/expanded state until some other invalidation (tab switch, scroll) forces
         * a recompose. Including the actual pref set here guarantees `.equals` differs.
         */
        val collapsedDynamicCategories: Set<String> = emptySet(),
        /**
         * Tracked alongside [collapsedDynamicCategories] for the same reason: toggling the
         * "Move collapsed to bottom" pref changes the sorted category iteration order, which
         * `Map.equals` doesn't see.
         */
        val collapsedDynamicAtBottom: Boolean = false,
        /**
         * The raw [library] after search + filter have been applied in the screen model
         * (Tier 2 phase 2A moved that I/O off the composable). The composable derives the
         * final rendered map from this via the cheap, pure category-visibility / collapse /
         * single-category pass. Defaulted so tabs that have not yet moved their pipeline keep
         * compiling.
         */
        val filteredLibrary: Map<C, List<T>> = emptyMap(),
        /** Series-type ids present in the library, for the filter sheet's Series type row. */
        val detectedTypes: Set<Int> = emptySet(),
        /** Display names of logged-in trackers, for the filter sheet's Tracker row. */
        val loggedTrackerNames: List<String> = emptyList(),
        /** Whether any library filter is active, for the toolbar filter-icon highlight. */
        val isAnyFilterActive: Boolean = false,
        // Display / badge / layout / category preferences (Tier 2 phase 2B moved these reads off
        // the composable). Defaulted so tabs that have not yet moved keep compiling. These ride a
        // flow combined AFTER the search/filter stage, so a change here updates the render without
        // re-running the suspend filter.
        val libraryLayout: Int = 0,
        val uniformGrid: Boolean = true,
        val useStaggeredGrid: Boolean = false,
        /** Raw `gridSize` pref; the composable derives the column count (needs screen width). */
        val gridSize: Float = 0f,
        val outlineOnCovers: Boolean = true,
        val showDownloadBadge: Boolean = false,
        val showLanguageBadge: Boolean = false,
        val unreadBadgeType: Int = 0,
        val hideStartReadingButton: Boolean = false,
        val showCategoryInTitle: Boolean = false,
        val showCategoryItemCounts: Boolean = false,
        val hideHopper: Boolean = false,
        val autohideHopper: Boolean = false,
        val hopperGravity: Int = 0,
        val hopperLongPressAction: Int = 0,
        val groupLibraryBy: Int = 0,
        val collapsedCategories: Set<String> = emptySet(),
        val showAllCategories: Boolean = false,
        val showEmptyCategoriesWhileFiltering: Boolean = false,
        val lastUsedCategoryOrder: Int = 0,
        val manualMerges: Set<String> = emptySet(),
    ) : LibraryTabState<T, C>
}
