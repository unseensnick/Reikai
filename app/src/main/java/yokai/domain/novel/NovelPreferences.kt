package yokai.domain.novel

import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ

class NovelPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /**
     * Set of plugin .js URLs the user has installed. The plugin id is intentionally not stored
     * here: it's read from each plugin's source after load. Storing URLs only means an unloadable
     * plugin (404, JSON parse error) can still be uninstalled by removing its URL.
     *
     * Slice C2 may grow this into a richer model (id + URL + version + repo origin) when the
     * add-repo Compose screen lands.
     */
    fun installedPluginUrls() = preferenceStore.getStringSet("ln_installed_plugin_urls", emptySet())

    // Reader rendering preferences. Stored globally for the spike; future polish may add
    // per-novel overrides if needed.
    fun readerFontSize() = preferenceStore.getInt("ln_reader_font_size_sp", 16)
    fun readerLineSpacing() = preferenceStore.getFloat("ln_reader_line_spacing", 1.5f)
    /** 0 = follow system, 1 = light, 2 = dark. */
    fun readerTheme() = preferenceStore.getInt("ln_reader_theme", 0)

    // Library update preferences. Defaults and key shapes mirror PreferencesHelper.libraryUpdate*
    // (manga side) so a user with parallel manga + novel libraries gets the same defaults on both.
    // Per Phase 7 Decision #2 these live here, not in PreferencesHelper, so the manga / novel
    // surfaces stay decoupled and can drift independently as the Novels tab matures.
    //
    // The MANGA_* restriction constants are reused as-is despite the name; they encode content-
    // agnostic status flags ("has unread", "not completed", "not started") that apply identically
    // to novels. Renaming them would churn manga code unnecessarily.
    fun libraryUpdateInterval() = preferenceStore.getInt("ln_library_update_interval", 24)
    fun libraryUpdateLastTimestamp() = preferenceStore.getLong("ln_library_update_last_timestamp", 0L)
    fun libraryUpdateDeviceRestriction() =
        preferenceStore.getStringSet("ln_library_update_restriction", setOf(DEVICE_ONLY_ON_WIFI))
    fun libraryUpdateNovelRestriction() =
        preferenceStore.getStringSet(
            "ln_library_update_novel_restriction",
            setOf(MANGA_HAS_UNREAD, MANGA_NON_COMPLETED, MANGA_NON_READ),
        )
    fun libraryUpdateCategories() = preferenceStore.getStringSet("ln_library_update_categories", emptySet())
    fun libraryUpdateCategoriesExclude() =
        preferenceStore.getStringSet("ln_library_update_categories_exclude", emptySet())

    /** Refresh novel covers on each update. Cover refresh isn't wired yet (Phase 7 doesn't add a
     *  novel CoverCache), but the pref is in place so the future setting toggles a real switch. */
    fun refreshCoversToo() = preferenceStore.getBoolean("ln_refresh_covers_too", true)
    fun hideNotificationContent() = preferenceStore.getBoolean("ln_hide_notification_content", false)
    /** 0 = never, 1 = ask, 2 = always. Mirrors manga's deleteRemovedChapters. */
    fun deleteRemovedChapters() = preferenceStore.getInt("ln_delete_removed_chapters", 0)

    // ----------------------------------------------------------------------------------------
    // Compose-library state, filter, sort, group, merge preferences (Phase 7C / 7E feed).
    //
    // Key prefix convention shift: these use `novel_` / `pref_novel_` per the Phase 7 plan
    // (C23), while the older keys above use `ln_` from the Slice C / Slice E spike. Each pref
    // is independent so the inconsistency is purely cosmetic; reorganising the older keys
    // would orphan any user data the spike produced.
    //
    // Defaults match the manga side (PreferencesHelper) so a dual-library user gets the same
    // out-of-box behavior on both tabs.
    // ----------------------------------------------------------------------------------------

    // Filter sheet. 0 = ignore, 1 = include, 2 = exclude (unread also uses 3 / 4 for the
    // read-progress refinement). Consumed by NovelLibraryFilter (C19).
    fun filterDownloaded() = preferenceStore.getInt("pref_novel_filter_downloaded_key", 0)
    fun filterUnread() = preferenceStore.getInt("pref_novel_filter_unread_key", 0)
    fun filterCompleted() = preferenceStore.getInt("pref_novel_filter_completed_key", 0)
    fun filterBookmarked() = preferenceStore.getInt("pref_novel_filter_bookmarked_key", 0)
    fun filterTracked() = preferenceStore.getInt("pref_novel_filter_tracked_key", 0)

    // Library-wide sort. Per-category overrides live on novel_categories.sort and read through
    // NovelCategory.novelSort. Consumed by NovelLibrarySort (C20).
    fun librarySortingMode() = preferenceStore.getInt("novel_library_sorting_mode", 0)
    fun librarySortingAscending() = preferenceStore.getBoolean("novel_library_sorting_ascending", true)

    // Grouping. Consumed by NovelLibrarySectioner (C17), NovelLibraryGrouping (C21), and
    // NovelLibraryDynamicGrouping (C22). `groupLibraryBy` mirrors LibraryGroup.BY_* ints
    // (0 = BY_DEFAULT). NovelLibraryDynamicGrouping rejects BY_LANGUAGE (no language field on
    // Novel) and returns empty; the future screen model should hide that chip on the novel side.
    fun groupLibraryBy() = preferenceStore.getInt("novel_group_library_by", 0)
    fun collapsedCategories() = preferenceStore.getStringSet("novel_collapsed_categories", mutableSetOf())
    fun collapsedDynamicCategories() =
        preferenceStore.getStringSet("novel_collapsed_dynamic_categories", mutableSetOf())
    fun collapsedDynamicAtBottom() = preferenceStore.getBoolean("novel_collapsed_dynamic_at_bottom", false)
    /** 0 = manual drag-and-drop order, 1 = A->Z, 2 = Z->A. Mirrors the Reikai-fork manga pref. */
    fun categorySortOrder() = preferenceStore.getInt("pref_novel_category_sort_order", 0)

    // Merge / unmerge. Raw String sets parsed by NovelLibraryGrouping (C21). Each entry is
    // "id,id[,id...]" for merges, "smallerId,largerId" for unmerges. Auto-merge collapses
    // same-titled novels across sources when on (the legacy default).
    fun novelManualMerges() = preferenceStore.getStringSet("novel_manual_merges", emptySet())
    fun novelManualUnmerges() = preferenceStore.getStringSet("novel_manual_unmerges", emptySet())
    fun autoMergeSameTitle() = preferenceStore.getBoolean("novel_auto_merge_same_title", true)

    // Screen state. lastUsedNovelCategory persists which category tab was last visible so the
    // user lands back on it after process death. showAllCategories renders every category
    // header even when one is filtered to empty. showEmptyCategoriesWhileFiltering keeps
    // emptied categories visible during an active filter narrow. filterOrder is the
    // reorderable filter-chip order (empty default; the future filter sheet UI will seed a
    // default ordering on first open).
    fun lastUsedNovelCategory() = preferenceStore.getInt("last_used_novel_category", 0)
    fun showAllCategories() = preferenceStore.getBoolean("novel_show_all_categories", true)
    fun showEmptyCategoriesWhileFiltering() =
        preferenceStore.getBoolean("novel_show_empty_categories_filtering", false)
    fun filterOrder() = preferenceStore.getString("novel_filter_order", "")
}
