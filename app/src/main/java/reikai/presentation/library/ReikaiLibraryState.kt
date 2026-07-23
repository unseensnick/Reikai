package reikai.presentation.library

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category

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
    val trackNovelUpdateErrors: Boolean = false,
)

/** Every Reikai library display preference, folded into one reactive state. */
@Suppress("UNCHECKED_CAST")
fun ReikaiLibraryPreferences.libraryStateFlow(): Flow<ReikaiLibraryState> = combine(
    groupLibraryBy.changes(),
    collapsedCategories.changes(),
    collapsedDynamicCategories.changes(),
    collapsedDynamicAtBottom.changes(),
    categorySortOrder.changes(),
    showCategoryInTitle.changes(),
    showAllCategories.changes(),
    showEmptyCategoriesWhileFiltering.changes(),
    hideHopper.changes(),
    autohideHopper.changes(),
    hopperGravity.changes(),
    hopperLongPressAction.changes(),
    showHiddenCategories.changes(),
    trackUpdateErrors.changes(),
    trackNovelUpdateErrors.changes(),
) {
    ReikaiLibraryState(
        groupLibraryBy = it[0] as Int,
        collapsedCategories = it[1] as Set<String>,
        collapsedDynamicCategories = it[2] as Set<String>,
        collapsedDynamicAtBottom = it[3] as Boolean,
        categorySortOrder = it[4] as Int,
        showCategoryInTitle = it[5] as Boolean,
        showAllCategories = it[6] as Boolean,
        showEmptyCategoriesWhileFiltering = it[7] as Boolean,
        hideHopper = it[8] as Boolean,
        autohideHopper = it[9] as Boolean,
        hopperGravity = it[10] as Int,
        hopperLongPressAction = it[11] as Int,
        showHiddenCategories = it[12] as Boolean,
        trackUpdateErrors = it[13] as Boolean,
        trackNovelUpdateErrors = it[14] as Boolean,
    )
}

/** Collapse or expand one real category, keyed by its header key. */
fun ReikaiLibraryPreferences.toggleCategoryCollapsed(headerKey: String) {
    collapsedCategories.toggle(headerKey)
}

/** Collapse or expand one dynamic group, keyed by its header key. */
fun ReikaiLibraryPreferences.toggleDynamicCategoryCollapsed(headerKey: String) {
    collapsedDynamicCategories.toggle(headerKey)
}

/** Collapse every key, or expand them all when they are already collapsed. */
fun ReikaiLibraryPreferences.expandOrCollapseAll(headerKeys: Set<String>) {
    val current = collapsedCategories.get()
    collapsedCategories.set(if (current.containsAll(headerKeys)) current - headerKeys else current + headerKeys)
}

/**
 * Toggle every displayed category collapsed or expanded (the hopper long-press), across both the real
 * categories and the dynamic groups, which are collapsed through separate preferences.
 */
fun ReikaiLibraryPreferences.toggleAllCategoriesCollapsed(categories: List<Category>) {
    val defaultKeys = categories.filterNot { ReikaiDynamicCategory.isDynamic(it) }
        .map { it.id.toString() }.toSet()
    val dynamicKeys = categories.filter { ReikaiDynamicCategory.isDynamic(it) }
        .map { ReikaiDynamicCategory.headerKey(it) }.toSet()
    val allCollapsed = collapsedCategories.get().containsAll(defaultKeys) &&
        collapsedDynamicCategories.get().containsAll(dynamicKeys)
    if (allCollapsed) {
        collapsedCategories.set(collapsedCategories.get() - defaultKeys)
        collapsedDynamicCategories.set(collapsedDynamicCategories.get() - dynamicKeys)
    } else {
        collapsedCategories.set(collapsedCategories.get() + defaultKeys)
        collapsedDynamicCategories.set(collapsedDynamicCategories.get() + dynamicKeys)
    }
}

private fun Preference<Set<String>>.toggle(key: String) {
    val current = get()
    set(if (key in current) current - key else current + key)
}
