package reikai.domain.library

import reikai.domain.novel.model.NovelLibrarySort
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

    /** Sticky Manga/Novels switch on the Library tab (P5 S6). Its own key, distinct from the Browse
     *  and download-queue content-type filters, so each surface remembers its last type. No ALL on
     *  the library: manga and novels never share one list. */
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

    // region Novel library sort/filter (P5 S6 slice 4; novel-specific keys, never collide with manga)

    /** Sort for the synthesized Default novel category (no DB row) + the seed for new categories.
     *  Stored as a [NovelLibrarySort] flag; per-category sorts live in `NovelCategory.flags`. */
    val novelLibraryDefaultSort: Preference<Long> =
        preferenceStore.getLong("novel_library_default_sort", NovelLibrarySort.default.toFlag())

    /** Stable seed for the novel library Random sort; regenerated when Random is (re)selected. */
    val novelLibraryRandomSeed: Preference<Long> = preferenceStore.getLong("novel_library_random_seed", 0L)

    val novelLibraryFilterDownloaded: Preference<TriState> =
        preferenceStore.getEnum("novel_library_filter_downloaded", TriState.DISABLED)
    val novelLibraryFilterUnread: Preference<TriState> =
        preferenceStore.getEnum("novel_library_filter_unread", TriState.DISABLED)
    val novelLibraryFilterStarted: Preference<TriState> =
        preferenceStore.getEnum("novel_library_filter_started", TriState.DISABLED)
    val novelLibraryFilterCompleted: Preference<TriState> =
        preferenceStore.getEnum("novel_library_filter_completed", TriState.DISABLED)
    val novelLibraryFilterBookmarked: Preference<TriState> =
        preferenceStore.getEnum("novel_library_filter_bookmarked", TriState.DISABLED)

    /**
     * Per-tracker novel library filter (tri-state), keyed by tracker id. Held under a separate key from
     * manga's `pref_filter_library_tracked_*` so a tracker filtered on one content type doesn't bleed
     * into the other; net-new, so no `_v2` migration suffix.
     */
    fun novelFilterTracking(id: Int): Preference<TriState> =
        preferenceStore.getEnum("novel_library_filter_tracked_$id", TriState.DISABLED)

    /** Master switch for the novel include/exclude category filter. */
    val novelLibraryFilterCategories: Preference<Boolean> =
        preferenceStore.getBoolean("novel_library_filter_categories", false)

    /** Category ids (as strings) a novel must belong to at least one of. Empty = no include constraint. */
    val novelLibraryFilterCategoriesInclude: Preference<Set<String>> =
        preferenceStore.getStringSet("novel_library_filter_categories_include", emptySet())

    /** Category ids (as strings) a novel must not belong to any of. */
    val novelLibraryFilterCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet("novel_library_filter_categories_exclude", emptySet())

    // endregion

    // region Merging (pref-based; no DB join table)

    /** Manual merge groups: each entry is a comma-joined, sorted manga-id group (e.g. "1,5,9"). */
    val mangaManualMerges: Preference<Set<String>> = preferenceStore.getStringSet(MANGA_MANUAL_MERGES_KEY, emptySet())

    /** Explicit unmerges: normalized "min,max" id pairs that must never be grouped. */
    val mangaManualUnmerges: Preference<Set<String>> = preferenceStore.getStringSet(MANGA_MANUAL_UNMERGES_KEY, emptySet())

    /** Auto-group favorited series that share a title across sources (guarded by the healing pass). */
    val autoMergeSameTitle: Preference<Boolean> = preferenceStore.getBoolean("auto_merge_same_title", true)

    /** On a merged library cover, show the grouped sources' icons instead of a numeric group count. */
    val showMergeSourceIcons: Preference<Boolean> = preferenceStore.getBoolean("merge_source_icons", true)

    /** Source ids ranked highest-priority-first; the trunk source when stitching a merged chapter list. */
    val preferredMangaSources: Preference<List<Long>> = preferenceStore.getLongArray("preferred_manga_sources", emptyList())

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

    // Novel merge (P5 S8). Keys preserved from the Yokai era for upgrade continuity.

    /** Manual novel merge groups: each entry is a comma-joined, sorted novel-id group (e.g. "1,5,9"). */
    val novelManualMerges: Preference<Set<String>> = preferenceStore.getStringSet(NOVEL_MANUAL_MERGES_KEY, emptySet())

    /** Explicit novel unmerges: normalized "min,max" id pairs that must never be grouped. */
    val novelManualUnmerges: Preference<Set<String>> = preferenceStore.getStringSet(NOVEL_MANUAL_UNMERGES_KEY, emptySet())

    /** Auto-group favorited novels that share a title across sources (see [novelAutoMergeRequireAuthor]). */
    val novelAutoMergeSameTitle: Preference<Boolean> = preferenceStore.getBoolean("novel_auto_merge_same_title", true)

    /** Guard for same-title auto-merge: also require a matching, non-blank author. Off = title-only (the
     *  legacy behavior). Re-evaluated on every group resolution, so it doubles as metadata healing. */
    val novelAutoMergeRequireAuthor: Preference<Boolean> =
        preferenceStore.getBoolean("novel_auto_merge_require_author", true)

    /** On a merged novel cover, show the grouped sources' icons instead of a numeric group count. */
    val showNovelMergeSourceIcons: Preference<Boolean> = preferenceStore.getBoolean("novel_merge_source_icons", true)

    // endregion

    companion object {
        // The merge prefs store entry IDs, which change on restore, so the backup restorer rebuilds them
        // from {url, source} refs and the generic preference restore must skip these keys (both manga
        // and novel; see BackupMangaMerge / BackupNovelMerge).
        const val MANGA_MANUAL_MERGES_KEY = "manga_manual_merges"
        const val MANGA_MANUAL_UNMERGES_KEY = "manga_manual_unmerges"
        const val NOVEL_MANUAL_MERGES_KEY = "novel_manual_merges"
        const val NOVEL_MANUAL_UNMERGES_KEY = "novel_manual_unmerges"
    }
}
