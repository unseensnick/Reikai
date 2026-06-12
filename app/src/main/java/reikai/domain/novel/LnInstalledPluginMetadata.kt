package reikai.domain.novel

import kotlinx.serialization.Serializable

/**
 * Per-plugin record persisted alongside [NovelPreferences.installedPluginUrls]. Keyed in
 * [NovelPreferences.installedPluginMetadata] by the canonicalized plugin .js URL (the same key the
 * installed-URLs set uses).
 *
 * `iconUrl` is the absolute CDN URL resolved by lnreader's plugins.min.json; the plugin's own
 * `plugin.icon` field is the relative authoring path and is not a usable URL. `version` is captured
 * so update detection can compare installed vs registry versions. `lang` is the registry language
 * tag (lnreader plugin classes do not expose lang at runtime), injected back into the host on load.
 */
@Serializable
data class LnInstalledPluginMetadata(
    val pluginId: String,
    val iconUrl: String? = null,
    val version: String? = null,
    val lang: String? = null,
)
