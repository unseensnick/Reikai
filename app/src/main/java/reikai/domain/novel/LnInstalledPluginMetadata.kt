package reikai.domain.novel

import kotlinx.serialization.Serializable

/**
 * Per-plugin record persisted alongside [NovelPreferences.installedPluginUrls], keyed in
 * [NovelPreferences.installedPluginMetadata] by the canonicalized plugin .js URL. `iconUrl` and
 * `customCssUrl` are the absolute CDN URLs from lnreader's plugins.min.json, since the plugin's own
 * fields are relative authoring paths. `version` is captured so update detection can compare installed
 * against registry. `lang` is the registry language tag, which plugin classes do not expose at runtime.
 */
@Serializable
data class LnInstalledPluginMetadata(
    val pluginId: String,
    val iconUrl: String? = null,
    val version: String? = null,
    val lang: String? = null,
    val customCssUrl: String? = null,
)
