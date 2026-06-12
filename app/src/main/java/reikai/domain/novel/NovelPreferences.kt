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

    companion object {
        private val metadataMapSerializer =
            MapSerializer(String.serializer(), LnInstalledPluginMetadata.serializer())
        private val metadataJson = Json { ignoreUnknownKeys = true }
    }
}
