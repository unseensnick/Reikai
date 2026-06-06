package yokai.domain.novel

import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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

    /** Most recently tapped LN source id. Drives the Last Used section header on the Browse →
     *  Light novel sources tab. Empty string when no source has been opened (fresh install). */
    fun lastUsedNovelSource() = preferenceStore.getString("last_used_novel_source", "")

    /**
     * Per-plugin metadata side-table keyed by the same canonicalized URL as [installedPluginUrls].
     * Carries the registry's `iconUrl` (so the LN sources list can render real icons) and
     * `version` (pre-positioned for future update detection). Missing entries default to null
     * fields; [installedPluginUrls] remains the authoritative installed-set.
     */
    fun installedPluginMetadata() = preferenceStore.getObject(
        key = "ln_installed_plugin_metadata",
        defaultValue = emptyMap(),
        serializer = { metadataJson.encodeToString(metadataMapSerializer, it) },
        deserializer = {
            runCatching { metadataJson.decodeFromString(metadataMapSerializer, it) }
                .getOrElse { emptyMap() }
        },
    )

    /**
     * Set of plugin REPO URLs (i.e. `plugins.min.json` registries) the user has added through
     * Browse → Extension repos → Light novels (Phase 8 follow-up CR1).
     *
     * Deliberately distinct from [installedPluginUrls]:
     *   - This pref tracks REPOS (sources of plugins).
     *   - That pref tracks the individual `.js` URLs the user has actually installed.
     * Deleting a repo here does NOT auto-uninstall the plugins it provided, matching the
     * lnreader upstream behavior. The Compose Browse → Extensions → Light novels sub-tab
     * (CR6) aggregates plugins across every URL in this set; the per-plugin install /
     * uninstall actions write through to [installedPluginUrls].
     */
    fun addedRepoUrls() = preferenceStore.getStringSet("novel_added_repo_urls", emptySet())

    /** Number of installed LN plugins whose stored version is older than the latest registry
     *  version. Written by [yokai.novel.update.LnPluginUpdateChecker]; combined with manga
     *  extension count to drive the Browse-tab badge in MainActivity. */
    fun pluginUpdatesCount() = preferenceStore.getInt("ln_plugin_updates_count", 0)

    /** Last successful update-check timestamp (millis). Gates the on-launch / on-resume path so
     *  it skips when run less than 6h ago. The periodic background job ignores this. */
    fun lastLnPluginCheck() = preferenceStore.getLong("ln_plugin_last_check", 0L)

    // Reader rendering preferences. Stored globally for the spike; future polish may add
    // per-novel overrides if needed.
    fun readerFontSize() = preferenceStore.getInt("ln_reader_font_size_sp", 16)
    fun readerLineSpacing() = preferenceStore.getFloat("ln_reader_line_spacing", 1.5f)
    /** 0 = follow system, 1 = light, 2 = dark. Legacy: consumed only by the plain-text reader that
     *  the WebView reader replaces (retired in the unified-reader Phase 1.7). The WebView reader uses
     *  [readerFollowSystemTheme] + [readerBackgroundColor]/[readerTextColor] instead. */
    fun readerTheme() = preferenceStore.getInt("ln_reader_theme", 0)
    /** CSS text-align for the chapter body: left / center / justify / right. */
    fun readerTextAlign() = preferenceStore.getString("ln_reader_text_align", "left")
    /** Bundled font family name (matches assets/fonts/<name>.ttf); empty = source's original font. */
    fun readerFontFamily() = preferenceStore.getString("ln_reader_font_family", "")
    /** Horizontal chapter padding in px. */
    fun readerPadding() = preferenceStore.getInt("ln_reader_padding", 16)
    /** When true the reader background/text follow the system light/dark setting (light/dark
     *  presets); when false the stored [readerBackgroundColor]/[readerTextColor] preset is used. */
    fun readerFollowSystemTheme() = preferenceStore.getBoolean("ln_reader_follow_system_theme", true)
    /** Chosen theme preset's background / text color (used when [readerFollowSystemTheme] is off).
     *  Default mirrors the LNReader dark preset. */
    fun readerBackgroundColor() = preferenceStore.getString("ln_reader_bg_color", "#292832")
    fun readerTextColor() = preferenceStore.getString("ln_reader_text_color", "#CCCCCC")

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
    /** 0 = ask (delete on a background update, prompt on the details page), 1 = always keep,
     *  2 = always delete. Mirrors manga's deleteRemovedChapters. */
    fun deleteRemovedChapters() = preferenceStore.getInt("ln_delete_removed_chapters", 0)

    /** Auto-download newly fetched chapters on a background library update. Independent of the
     *  manga `PreferencesHelper.downloadNewChapters` toggle (Decision #2 keeps the surfaces
     *  decoupled). Default off. */
    fun downloadNewChapters() = preferenceStore.getBoolean("novel_download_new_chapters", false)

    /** Delete a novel chapter's offline download once it's marked read. Independent of the manga
     *  `PreferencesHelper.removeAfterMarkedAsRead` toggle (Decision #2 keeps the surfaces
     *  decoupled). Default off. */
    fun removeAfterMarkedAsRead() = preferenceStore.getBoolean("novel_remove_after_marked_as_read", false)

    // Details-screen chapter sort / filter / display defaults (parallel to PreferencesHelper's
    // defaultChapter* prefs). A novel uses these unless it sets its own local override in
    // Novel.chapterFlags. Default sort is source order ascending (chapter 1 first), the novel
    // reading order, which differs from the manga default (newest first).
    fun defaultChapterSortOrder() = preferenceStore.getInt("novel_default_chapter_sort", Manga.CHAPTER_SORTING_SOURCE)
    fun defaultChapterSortDescending() = preferenceStore.getBoolean("novel_default_chapter_sort_desc", false)
    fun defaultChapterFilterUnread() = preferenceStore.getInt("novel_default_chapter_filter_unread", Manga.SHOW_ALL)
    fun defaultChapterFilterBookmarked() = preferenceStore.getInt("novel_default_chapter_filter_bookmarked", Manga.SHOW_ALL)
    fun defaultChapterHideTitles() = preferenceStore.getBoolean("novel_default_chapter_hide_titles", false)

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
    /** Per-seed shuffle for Random mode. Re-rolled whenever the user taps Random in the sort
     *  sheet so the order changes; preserved across re-emissions so a reload doesn't reshuffle.
     *  Stored as Int so the existing PreferenceStore Int writer applies; widened to Long when
     *  passed to [NovelLibrarySort] which expects a Long seed. */
    fun randomSortSeed() = preferenceStore.getInt("novel_random_sort_seed", 0)

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

    /** Ordered preferred-source ranking (highest priority first), slash-joined NovelSource ids.
     *  NovelChapterAggregation reads it to pick the trunk of a merged chapter list, mirroring the
     *  manga side's PreferencesHelper.preferredSources (which uses Long ids). */
    fun novelPreferredSources() = preferenceStore.getString("novel_preferred_sources", "")

    /** Chapters the user has manually hidden (e.g. a source's own duplicate). Each entry is the
     *  chapter's stable identity "novelId|url"; the details screen filters these out of the list.
     *  Pref-based (not a chapter column) so it survives a source re-sync for free, like the merge sets. */
    fun hiddenChapters() = preferenceStore.getStringSet("novel_hidden_chapters", emptySet())

    // ----------------------------------------------------------------------------------------
    // Per-library visual preferences (Phase E). Only consulted when
    // [yokai.domain.base.BasePreferences.useSharedLibraryDisplayPrefs] is false; in shared mode
    // the novel library reads from PreferencesHelper / UiPreferences (manga side) instead, so
    // these sit dormant. The Phase E one-time migration seeds them from the manga values on
    // upgrade so flipping to independent mode keeps the user's current look. Defaults below
    // mirror the manga defaults so a fresh install with shared-off still looks identical.
    // ----------------------------------------------------------------------------------------

    /** Mirrors [eu.kanade.tachiyomi.data.preference.PreferencesHelper.libraryLayout]
     *  (default LibraryItem.LAYOUT_COMFORTABLE_GRID = 2). */
    fun novelLibraryLayout() = preferenceStore.getInt("novel_library_layout", 2)
    /** Mirrors PreferencesHelper.gridSize (default 1.0f). */
    fun novelGridSize() = preferenceStore.getFloat("pref_novel_grid_size", 1f)
    /** Mirrors PreferencesHelper.useStaggeredGrid (default false). */
    fun novelUseStaggeredGrid() = preferenceStore.getBoolean("novel_use_staggered_grid", false)
    /** Mirrors yokai.domain.ui.UiPreferences.uniformGrid (default true). */
    fun novelUniformGrid() = preferenceStore.getBoolean("novel_uniform_grid", true)
    /** Mirrors yokai.domain.ui.UiPreferences.outlineOnCovers (default true). */
    fun novelOutlineOnCovers() = preferenceStore.getBoolean("novel_outline_on_covers", true)
    /** Mirrors PreferencesHelper.unreadBadgeType (default 2 = show count). */
    fun novelUnreadBadgeType() = preferenceStore.getInt("novel_unread_badge_type", 2)
    /** Mirrors PreferencesHelper.downloadBadge (default false). Reserved for the future novel
     *  downloads feature; the toggle exists so the Display sheet's surface is complete. */
    fun novelDownloadBadge() = preferenceStore.getBoolean("novel_download_badge", false)
    /** Mirrors PreferencesHelper.languageBadge (default false). */
    fun novelLanguageBadge() = preferenceStore.getBoolean("novel_language_badge", false)
    /** Mirrors PreferencesHelper.hideStartReadingButton (default false). */
    fun novelHideStartReadingButton() = preferenceStore.getBoolean("novel_hide_start_reading_button", false)
    /** Mirrors PreferencesHelper.categoryNumberOfItems (default false). */
    fun novelCategoryNumberOfItems() = preferenceStore.getBoolean("novel_category_number_of_items", false)

    /** List vs grid for the LN source browse screen. Browse-specific (the manga catalogue's
     *  [eu.kanade.tachiyomi.data.preference.PreferencesHelper.browseAsList] parallel), kept separate
     *  from the library layout so toggling browse display doesn't reshape the Novels library. The
     *  browse grid otherwise borrows the manga catalogue's sizing (grid size + density) so the two
     *  Browse surfaces match. */
    fun novelBrowseAsList() = preferenceStore.getBoolean("novel_browse_as_list", false)

    /** Default category when adding a novel to the library from browse, mirroring the manga
     *  [eu.kanade.tachiyomi.data.preference.PreferencesHelper.defaultCategory]: -2 = last used,
     *  -1 = always ask (show the category sheet), 0 = Default (uncategorized), >0 = a specific
     *  category id. */
    fun novelDefaultCategory() = preferenceStore.getInt("novel_default_category", -2)

    // Screen state. lastUsedNovelCategory persists which category tab was last visible so the
    // user lands back on it after process death. showAllCategories renders every category
    // header even when one is filtered to empty. showEmptyCategoriesWhileFiltering keeps
    // emptied categories visible during an active filter narrow. filterOrder is the
    // reorderable filter-chip order; it shares the manga DEFAULT_ORDER so the Novels filter
    // sheet renders the applicable chips (the manga-only Series type / Content type / Tracked
    // entries in that order auto-hide on the Novels tab).
    fun lastUsedNovelCategory() = preferenceStore.getInt("last_used_novel_category", 0)
    fun showAllCategories() = preferenceStore.getBoolean("novel_show_all_categories", true)
    fun showEmptyCategoriesWhileFiltering() =
        preferenceStore.getBoolean("novel_show_empty_categories_filtering", false)
    fun filterOrder() = preferenceStore.getString("novel_filter_order", FilterBottomSheet.Filters.DEFAULT_ORDER)

    // Category-hopper prefs (independent from the manga hopper, like the other category-
    // navigation prefs). Defaults match the manga hopper so the novel hopper behaves the same
    // out of the box. Gravity is written only via the drag gesture, not a settings row.
    fun novelHideHopper() = preferenceStore.getBoolean("novel_hide_hopper", false)
    fun novelAutohideHopper() = preferenceStore.getBoolean("novel_autohide_hopper", true)
    fun novelHopperGravity() = preferenceStore.getInt("novel_hopper_gravity", 1)
    fun novelHopperLongPressAction() = preferenceStore.getInt("novel_hopper_long_press", 0)

    companion object {
        private val metadataMapSerializer =
            MapSerializer(String.serializer(), LnInstalledPluginMetadata.serializer())
        private val metadataJson = Json { ignoreUnknownKeys = true }
    }
}
