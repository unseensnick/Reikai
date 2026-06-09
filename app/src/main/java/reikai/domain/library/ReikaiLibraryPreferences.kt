package reikai.domain.library

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum

/**
 * Reikai's net-new library preferences, the ones Mihon's [tachiyomi.domain.library.service.LibraryPreferences]
 * does not have. Kept in a separate holder so Mihon's class stays untouched and upstream-mergeable.
 *
 * Key strings are preserved verbatim from the Yōkai-era fork so an in-place upgrade keeps the
 * user's library display settings (preferences live in SharedPreferences, independent of the DB).
 */
class ReikaiLibraryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // region Grouping

    /** Dynamic grouping mode (eu.kanade.tachiyomi.ui.library.LibraryGroup; 0 = BY_DEFAULT). */
    val groupLibraryBy: Preference<Int> = preferenceStore.getInt("group_library_by", 0)

    /** Header keys of collapsed user categories (BY_DEFAULT grouping). */
    val collapsedCategories: Preference<Set<String>> = preferenceStore.getStringSet("collapsed_categories", emptySet())

    /** Header keys of collapsed dynamic-group categories. */
    val collapsedDynamicCategories: Preference<Set<String>> =
        preferenceStore.getStringSet("collapsed_dynamic_categories", emptySet())

    /** Push collapsed dynamic groups to the bottom of the list. */
    val collapsedDynamicAtBottom: Preference<Boolean> = preferenceStore.getBoolean("collapsed_dynamic_at_bottom", false)

    /** Category list ordering: 0 = manual (Category.order), 1 = A→Z, 2 = Z→A. */
    val categorySortOrder: Preference<Int> = preferenceStore.getInt("pref_category_sort_order", 0)

    // endregion

    // region Badges

    /** Show the source/extension icon on each cover. */
    val sourceBadge: Preference<Boolean> = preferenceStore.getBoolean("source_badge", true)

    // endregion

    // region Category display

    val showCategoryInTitle: Preference<Boolean> = preferenceStore.getBoolean("category_in_title", false)

    // Default off so Mihon's swipeable category pager stays the default; the single-list view
    // (show-all) is the opt-in Reikai addition. Toggle lives in the library display settings.
    val showAllCategories: Preference<Boolean> = preferenceStore.getBoolean("show_all_categories", false)

    val showEmptyCategoriesWhileFiltering: Preference<Boolean> =
        preferenceStore.getBoolean("show_empty_categories_filtering", false)

    // Off so a hidden category stays hidden; turning this on reveals hidden categories in the library.
    val showHiddenCategories: Preference<Boolean> = preferenceStore.getBoolean("show_hidden_categories", false)

    // endregion

    // region Hopper

    val hideHopper: Preference<Boolean> = preferenceStore.getBoolean("hide_hopper", false)

    val autohideHopper: Preference<Boolean> = preferenceStore.getBoolean("autohide_hopper", true)

    /** Hopper position gravity (0 = left, 1 = center, 2 = right). */
    val hopperGravity: Preference<Int> = preferenceStore.getInt("hopper_gravity", 1)

    /** Hopper long-press action index (search / expand-collapse / display / group / random / random-global). */
    val hopperLongPressAction: Preference<Int> = preferenceStore.getInt("hopper_long_press", 0)

    // endregion

    // region Filters (net-new dims Mihon lacks; ported from Komikku, re-typed onto Mihon)

    /** Adult-content filter. Komikku's `filterLewd`; lewdness derived heuristically (see reikai.util.isLewd). */
    val filterLewd: Preference<TriState> = preferenceStore.getEnum("pref_filter_library_lewd", TriState.DISABLED)

    /** Master switch for the include/exclude category filter. */
    val filterCategories: Preference<Boolean> = preferenceStore.getBoolean("pref_filter_library_categories", false)

    /** Category ids (as strings) a manga must belong to at least one of. Empty = no include constraint. */
    val filterCategoriesInclude: Preference<Set<String>> =
        preferenceStore.getStringSet("pref_filter_library_categories_include", emptySet())

    /** Category ids (as strings) a manga must not belong to any of. */
    val filterCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet("pref_filter_library_categories_exclude", emptySet())

    // endregion
}
