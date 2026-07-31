package reikai.domain.library

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray

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

    /** Dynamic grouping mode for the novel library; separate key because novel ids/categories are
     *  a separate space from manga. */
    val groupNovelLibraryBy: Preference<Int> = preferenceStore.getInt("group_novel_library_by", 0)

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

    // region Content type

    /** Sticky All/Manga/Novels chip on the Library tab. Its own key, distinct from the Browse
     *  and download-queue content-type filters, so each surface remembers its last type. */
    val libraryContentType: Preference<ContentType> =
        preferenceStore.getEnum("library_content_type", ContentType.MANGA)

    // endregion

    // region Badges

    /** Show the source/extension icon on each cover. */
    val sourceBadge: Preference<Boolean> = preferenceStore.getBoolean("source_badge", true)

    // endregion

    // region Update errors

    /** Opt-in: record manga library update failures and expose them in the Update errors screen. */
    val trackUpdateErrors: Preference<Boolean> = preferenceStore.getBoolean("track_update_errors", false)

    /** Opt-in: record novel library update failures and expose them in the Update errors screen. */
    val trackNovelUpdateErrors: Preference<Boolean> = preferenceStore.getBoolean("track_novel_update_errors", false)

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

    // region Novel library filters: RETIRED
    // The library filter preferences unified onto the manga keys (2026-07-31), following the sort
    // unification: a filter describes the list, not a content type. Every `novel_library_filter_*` key
    // (the tri-state axes, lewd, the per-tracker filters, the category include/exclude set) is dead and
    // skipped on restore via DEAD_NOVEL_FILTER_KEY_PREFIX below, like the sort keys. The novel global
    // sort and Random seed keys retired the same way (DEAD_NOVEL_SORT_KEY).
    // endregion

    // region Merging (persisted group tables; the pref-based keys below are migrated then kept for backup)

    /** Master switch for source merging (manga + novels). Off resolves every series standalone and hides
     *  the merge UI without deleting groups, so turning it back on restores them. Lives in Settings. */
    val seriesMergingEnabled: Preference<Boolean> = preferenceStore.getBoolean("series_merging_enabled", true)

    /** Manual merge groups: each entry is a comma-joined, sorted manga-id group (e.g. "1,5,9"). */
    val mangaManualMerges: Preference<Set<String>> = preferenceStore.getStringSet(MANGA_MANUAL_MERGES_KEY, emptySet())

    /** Explicit unmerges: normalized "min,max" id pairs that must never be grouped. */
    val mangaManualUnmerges: Preference<Set<String>> = preferenceStore.getStringSet(
        MANGA_MANUAL_UNMERGES_KEY,
        emptySet(),
    )

    /** Auto-group favorited series that share a title across sources (guarded by the healing pass). */
    val autoMergeSameTitle: Preference<Boolean> = preferenceStore.getBoolean("auto_merge_same_title", true)

    /** On a merged library cover, show the grouped sources' icons instead of a numeric group count. */
    val showMergeSourceIcons: Preference<Boolean> = preferenceStore.getBoolean("merge_source_icons", true)

    /** Source ids ranked highest-priority-first; the trunk source when stitching a merged chapter list. */
    val preferredMangaSources: Preference<List<Long>> = preferenceStore.getLongArray(
        "preferred_manga_sources",
        emptyList(),
    )

    /** Novel-source ids ranked highest-priority-first; the trunk source for a merged novel chapter list.
     *  Novel source ids are Strings (plugin slugs), so this is a newline-joined ordered list. */
    val preferredNovelSources: Preference<List<String>> = preferenceStore.getObjectFromString(
        key = "preferred_novel_sources",
        defaultValue = emptyList(),
        serializer = { it.joinToString("\n") },
        deserializer = { it.split("\n").filter(String::isNotBlank) },
    )

    /** Mirror a tracker added to one source onto every favorited member of its merged group. */
    val syncTrackerLinksGrouped: Preference<Boolean> = preferenceStore.getBoolean("sync_tracker_links_grouped", true)

    // Novel merge. Keys preserved from the Yokai era for upgrade continuity.

    /** Manual novel merge groups: each entry is a comma-joined, sorted novel-id group (e.g. "1,5,9"). */
    val novelManualMerges: Preference<Set<String>> = preferenceStore.getStringSet(NOVEL_MANUAL_MERGES_KEY, emptySet())

    /** Explicit novel unmerges: normalized "min,max" id pairs that must never be grouped. */
    val novelManualUnmerges: Preference<Set<String>> = preferenceStore.getStringSet(
        NOVEL_MANUAL_UNMERGES_KEY,
        emptySet(),
    )

    /** Whether adding a same-titled novel offers to group it with the match (the novel twin of
     *  [autoMergeSameTitle]). Repurposed from silent auto-merge: grouping is now an explicit choice. */
    val novelAutoMergeSameTitle: Preference<Boolean> = preferenceStore.getBoolean("novel_auto_merge_same_title", true)

    /** Read only by the one-time pref-to-group migration, which honors it when reconstructing the groups
     *  a same-title auto-merge would have formed. Nothing resolves membership from author any more, so it
     *  is not surfaced in Settings; it stays declared because that migration still reads it. */
    val novelAutoMergeRequireAuthor: Preference<Boolean> =
        preferenceStore.getBoolean("novel_auto_merge_require_author", true)

    // The novel merge-source-icons key ("novel_merge_source_icons") is retired with the filter keys:
    // both content types read [showMergeSourceIcons]. Skipped on restore (DEAD_NOVEL_MERGE_ICONS_KEY).

    // endregion

    companion object {
        // The merge prefs store entry IDs, which change on restore, so the backup restorer rebuilds them
        // from {url, source} refs and the generic preference restore must skip these keys (both manga
        // and novel; see BackupMangaMerge / BackupNovelMerge).
        const val MANGA_MANUAL_MERGES_KEY = "manga_manual_merges"
        const val MANGA_MANUAL_UNMERGES_KEY = "manga_manual_unmerges"
        const val NOVEL_MANUAL_MERGES_KEY = "novel_manual_merges"
        const val NOVEL_MANUAL_UNMERGES_KEY = "novel_manual_unmerges"

        // Retired novel global-sort keys: both content types now read LibraryPreferences.sortingMode /
        // randomSortSeed. The stored value was dropped, not migrated, because a value written before the
        // category unification can carry the old flag layout (Downloaded/TrackerMean swapped) and the two
        // readings are indistinguishable. Skipped on restore so an old backup can't resurrect them.
        const val DEAD_NOVEL_SORT_KEY = "novel_library_default_sort"
        const val DEAD_NOVEL_RANDOM_SEED_KEY = "novel_library_random_seed"

        // Retired novel filter keys (every novel_library_filter_* key: the tri-state axes, lewd, the
        // per-tracker filters, the category include/exclude set), plus the novel merge-icons toggle:
        // the library filter preferences unified onto the manga keys. Values dropped, not migrated
        // (filters are casually re-picked); skipped on restore so an old backup can't resurrect them.
        const val DEAD_NOVEL_FILTER_KEY_PREFIX = "novel_library_filter_"
        const val DEAD_NOVEL_MERGE_ICONS_KEY = "novel_merge_source_icons"
    }
}
