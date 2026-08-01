package reikai.presentation.library

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category

/**
 * Reikai's net-new library display state, bundled into one object rather than ~18 loose fields. Fed from
 * [reikai.domain.library.ReikaiLibraryPreferences] and read through [LibraryEngine.display]: it is
 * library-wide, so it belongs to the engine and not to either content type's model.
 */
@Immutable
data class ReikaiLibraryState(
    // Only what the TAB reads. The assembly-side preferences (grouping mode, category sort order,
    // hidden categories, collapsed-at-bottom) feed LibraryEngine's own prefs flow instead; carrying
    // them here too left dead fields a future reader could wrongly trust.
    val collapsedCategories: Set<String> = emptySet(),
    val collapsedDynamicCategories: Set<String> = emptySet(),
    val showCategoryInTitle: Boolean = false,
    val showAllCategories: Boolean = true,
    val hideHopper: Boolean = false,
    val autohideHopper: Boolean = true,
    val hopperGravity: Int = 1,
    val hopperLongPressAction: Int = 0,
    val trackUpdateErrors: Boolean = false,
    val trackNovelUpdateErrors: Boolean = false,
)

/**
 * The whole library's display config, Reikai's own preferences plus the two Mihon ones the shared tab
 * reads. One holder for the library rather than one per content type, because none of these settings is
 * per-type: the chip changes what is listed, not how it is drawn.
 */
@Immutable
data class LibraryDisplayState(
    val reikai: ReikaiLibraryState = ReikaiLibraryState(),
    val showCategoryTabs: Boolean = false,
    val showItemCounts: Boolean = false,
)

/** Every Reikai library display preference, folded into one reactive state. */
@Suppress("UNCHECKED_CAST")
fun ReikaiLibraryPreferences.libraryStateFlow(): Flow<ReikaiLibraryState> = combine(
    collapsedCategories.changes(),
    collapsedDynamicCategories.changes(),
    showCategoryInTitle.changes(),
    showAllCategories.changes(),
    hideHopper.changes(),
    autohideHopper.changes(),
    hopperGravity.changes(),
    hopperLongPressAction.changes(),
    trackUpdateErrors.changes(),
    trackNovelUpdateErrors.changes(),
) {
    ReikaiLibraryState(
        collapsedCategories = it[0] as Set<String>,
        collapsedDynamicCategories = it[1] as Set<String>,
        showCategoryInTitle = it[2] as Boolean,
        showAllCategories = it[3] as Boolean,
        hideHopper = it[4] as Boolean,
        autohideHopper = it[5] as Boolean,
        hopperGravity = it[6] as Int,
        hopperLongPressAction = it[7] as Int,
        trackUpdateErrors = it[8] as Boolean,
        trackNovelUpdateErrors = it[9] as Boolean,
    )
}

/** Collapse or expand one real category, keyed by its header key. */
fun ReikaiLibraryPreferences.toggleCategoryCollapsed(headerKey: String) {
    collapsedCategories.toggle(headerKey)
}

/** Collapse or expand one dynamic group, keyed by its normalized header key. Removal matches by
 *  normalized form so entries persisted before normalization still toggle off. */
fun ReikaiLibraryPreferences.toggleDynamicCategoryCollapsed(headerKey: String) {
    val current = collapsedDynamicCategories.get()
    val equivalent = current.filterTo(HashSet()) { ReikaiDynamicCategory.normalizeKey(it) == headerKey }
    collapsedDynamicCategories.set(if (equivalent.isNotEmpty()) current - equivalent else current + headerKey)
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
    // Dynamic keys compare and clear by normalized form, so pre-normalization entries count as
    // collapsed and expand-all actually removes them.
    val storedDynamic = collapsedDynamicCategories.get()
    val storedDynamicNormalized = storedDynamic.mapTo(HashSet(), ReikaiDynamicCategory::normalizeKey)
    val allCollapsed = collapsedCategories.get().containsAll(defaultKeys) &&
        storedDynamicNormalized.containsAll(dynamicKeys)
    if (allCollapsed) {
        collapsedCategories.set(collapsedCategories.get() - defaultKeys)
        collapsedDynamicCategories.set(
            storedDynamic.filterNotTo(HashSet()) { ReikaiDynamicCategory.normalizeKey(it) in dynamicKeys },
        )
    } else {
        collapsedCategories.set(collapsedCategories.get() + defaultKeys)
        collapsedDynamicCategories.set(storedDynamic + dynamicKeys)
    }
}

private fun Preference<Set<String>>.toggle(key: String) {
    val current = get()
    set(if (key in current) current - key else current + key)
}
