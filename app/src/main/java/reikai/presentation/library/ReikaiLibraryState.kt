package reikai.presentation.library

import androidx.compose.runtime.Immutable

/**
 * Reikai's net-new library display state, bundled into one object so Mihon's
 * `LibraryScreenModel.State` carries a single extra field instead of ~18 loose ones.
 * Fed from [reikai.domain.library.ReikaiLibraryPreferences]; consumed by the Reikai library renderer.
 */
@Immutable
data class ReikaiLibraryState(
    val groupLibraryBy: Int = LibraryGroup.BY_DEFAULT,
    val collapsedCategories: Set<String> = emptySet(),
    val collapsedDynamicCategories: Set<String> = emptySet(),
    val collapsedDynamicAtBottom: Boolean = false,
    val categorySortOrder: Int = 0,
    val showCategoryInTitle: Boolean = false,
    val showAllCategories: Boolean = true,
    val showEmptyCategoriesWhileFiltering: Boolean = false,
    val showHiddenCategories: Boolean = false,
    val hideHopper: Boolean = false,
    val autohideHopper: Boolean = true,
    val hopperGravity: Int = 1,
    val hopperLongPressAction: Int = 0,
    val trackUpdateErrors: Boolean = false,
)
