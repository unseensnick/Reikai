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
}
