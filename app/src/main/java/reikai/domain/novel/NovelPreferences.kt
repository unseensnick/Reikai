package reikai.domain.novel

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore

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

    companion object {
        private val metadataMapSerializer =
            MapSerializer(String.serializer(), LnInstalledPluginMetadata.serializer())
        private val metadataJson = Json { ignoreUnknownKeys = true }
    }
}
