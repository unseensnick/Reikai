package reikai.domain.novel

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Net-new preferences for the light-novel vertical. Only the subset the plugin host / source /
 * install / update layers need lands here (S2); later stages (reader, library, merge) grow this
 * holder. Key strings are preserved from the Yōkai-era fork so an in-place upgrade keeps state.
 */
class NovelPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Canonicalized plugin .js URLs the user has installed. The plugin id is not stored here: it is
     * read from each plugin after load, so an unloadable plugin (404, parse error) can still be
     * uninstalled by removing its URL.
     */
    fun installedPluginUrls() = preferenceStore.getStringSet("ln_installed_plugin_urls", emptySet())

    /**
     * Per-plugin metadata side-table keyed by the same canonicalized URL as [installedPluginUrls].
     * Carries the registry's icon URL (so the sources list renders real icons), version, and lang.
     */
    fun installedPluginMetadata() = preferenceStore.getObjectFromString(
        key = "ln_installed_plugin_metadata",
        defaultValue = emptyMap(),
        serializer = { metadataJson.encodeToString(metadataMapSerializer, it) },
        deserializer = {
            runCatching { metadataJson.decodeFromString(metadataMapSerializer, it) }.getOrElse { emptyMap() }
        },
    )

    /** Most recently tapped LN source id (drives the Last Used section on the sources list). */
    fun lastUsedNovelSource() = preferenceStore.getString("last_used_novel_source", "")

    /**
     * Plugin repo URLs (i.e. `plugins.min.json` registries) the user added. Distinct from
     * [installedPluginUrls]: this tracks repos (sources of plugins); that tracks the individual
     * `.js` URLs actually installed.
     */
    fun addedRepoUrls() = preferenceStore.getStringSet("novel_added_repo_urls", emptySet())

    /** Count of installed plugins whose stored version is older than the latest registry version. */
    fun pluginUpdatesCount() = preferenceStore.getInt("ln_plugin_updates_count", 0)

    /** Last successful update-check timestamp (millis); gates the on-launch path to skip if recent. */
    fun lastLnPluginCheck() = preferenceStore.getLong("ln_plugin_last_check", 0L)

    // Global chapter sort / filter / display defaults (S3c). A novel falls back to these unless its
    // own `chapterFlags` local bit is set (see [reikai.domain.novel.model.NovelChapterFlags]). Stored
    // as the same bitmask values the per-novel flags use, so "Set as default" is a straight copy.

    /** Default chapter sort method (source order / number / upload date) as the SORTING_MASK bits. */
    fun defaultChapterSortOrder() = preferenceStore.getLong("ln_default_chapter_sort", 0L)

    /** Default chapter sort direction; true = newest/highest first (matches the manga default). */
    fun defaultChapterSortDescending() = preferenceStore.getBoolean("ln_default_chapter_sort_desc", true)

    /** Default unread filter as the READ_MASK bits (0 = show all). */
    fun defaultChapterFilterUnread() = preferenceStore.getLong("ln_default_chapter_filter_unread", 0L)

    /** Default bookmarked filter as the BOOKMARKED_MASK bits (0 = show all). */
    fun defaultChapterFilterBookmarked() = preferenceStore.getLong("ln_default_chapter_filter_bookmarked", 0L)

    /** Default display: true shows "Chapter N", false shows the source chapter title. */
    fun defaultChapterHideTitles() = preferenceStore.getBoolean("ln_default_chapter_hide_titles", false)

    /**
     * Chapters the user manually hid (e.g. a source's own duplicate listings). Each entry is the
     * restore-stable key `"<source>|<chapterUrl>"` (no local novel id, so it survives a backup
     * restore where novels are re-inserted with new ids). Backed up automatically as a normal pref.
     */
    fun hiddenChapters() = preferenceStore.getStringSet("novel_hidden_chapters", emptySet())

    // Reader display + theme (S4). Fed into the WebView reader's `--readerSettings-*` CSS vars and
    // pushed live on change. Key strings preserved from the Yōkai-era fork for upgrade continuity.

    fun readerFontSize() = preferenceStore.getInt("ln_reader_font_size_sp", 16)
    fun readerLineSpacing() = preferenceStore.getFloat("ln_reader_line_spacing", 1.5f)
    fun readerTextAlign() = preferenceStore.getString("ln_reader_text_align", "left")
    fun readerFontFamily() = preferenceStore.getString("ln_reader_font_family", "")
    fun readerPadding() = preferenceStore.getInt("ln_reader_padding", 16)

    /** When true the reader follows the system light/dark mode; otherwise the chosen preset wins. */
    fun readerFollowSystemTheme() = preferenceStore.getBoolean("ln_reader_follow_system_theme", true)
    fun readerBackgroundColor() = preferenceStore.getString("ln_reader_bg_color", "#292832")
    fun readerTextColor() = preferenceStore.getString("ln_reader_text_color", "#CCCCCC")

    /** When on, the reader's next/previous skip a chapter whose number matches the one just read (the
     *  same-number duplicates a cross-source merge produces). Reading-navigation only, non-destructive. */
    fun readerSkipDuplicateChapters() = preferenceStore.getBoolean("ln_reader_skip_duplicate_chapters", false)

    // Downloads (S5). Key strings preserved from the Yōkai-era fork for upgrade continuity.

    /** Delete a downloaded chapter's offline copy once it's marked read. */
    fun removeAfterMarkedAsRead() = preferenceStore.getBoolean("novel_remove_after_marked_as_read", false)

    /** Auto-download newly fetched chapters when an update is detected. The pref + manager plumbing
     *  land in S5; the update-detection trigger that consumes it is wired in S7. */
    fun downloadNewChapters() = preferenceStore.getBoolean("novel_download_new_chapters", false)

    /** When auto-downloading, skip a new chapter whose number matches one already read (avoids
     *  re-downloading a source's duplicate listing of a chapter you've finished). */
    fun downloadNewUnreadChaptersOnly() =
        preferenceStore.getBoolean("novel_download_new_unread_chapters_only", false)

    /** Restrict auto-download to novels in these categories (empty = all). Mirrors the manga keys. */
    fun downloadNewChapterCategories() = preferenceStore.getStringSet("novel_download_new_categories", emptySet())

    fun downloadNewChapterCategoriesExclude() =
        preferenceStore.getStringSet("novel_download_new_categories_exclude", emptySet())

    // Background chapter updates (S7).

    /** How often the background novel-update job runs, in hours. 0 = off (the default, matching the
     *  manga library's off-by-default); 12/24/48/72/168 mirror the manga interval options. */
    fun libraryUpdateInterval() = preferenceStore.getInt("novel_library_update_interval", 0)

    /** Device conditions gating the background job, reusing the manga restriction keys
     *  ([LibraryPreferences.DEVICE_ONLY_ON_WIFI] etc.) so the same Constraints builder applies. */
    fun libraryUpdateDeviceRestrictions() =
        preferenceStore.getStringSet(
            "novel_library_update_restrictions",
            setOf(LibraryPreferences.DEVICE_ONLY_ON_WIFI),
        )

    /** Skip novels whose source status is Completed (the novel analog of "only update ongoing"). */
    fun updateOnlyOngoing() = preferenceStore.getBoolean("novel_library_update_only_ongoing", false)

    companion object {
        private val metadataMapSerializer =
            MapSerializer(String.serializer(), LnInstalledPluginMetadata.serializer())
        private val metadataJson = Json { ignoreUnknownKeys = true }
    }
}
