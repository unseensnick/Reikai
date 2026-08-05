package reikai.domain.novel

import kotlinx.serialization.Serializable

/**
 * Per-plugin record persisted alongside [NovelPreferences.installedPluginUrls], keyed in
 * [NovelPreferences.installedPluginMetadata] by the canonicalized plugin .js URL. `iconUrl` is the
 * absolute CDN URL from lnreader's plugins.min.json, since the plugin's own `plugin.icon` is a
 * relative authoring path and not a usable URL. `version` is captured so update detection can compare
 * installed against registry. `lang` is the registry language tag, which plugin classes do not expose
 * at runtime, injected back into the host on load.
 */
@Serializable
data class LnInstalledPluginMetadata(
    val pluginId: String,
    val iconUrl: String? = null,
    val version: String? = null,
    val lang: String? = null,
)
