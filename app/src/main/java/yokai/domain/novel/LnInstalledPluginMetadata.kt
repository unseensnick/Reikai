package yokai.domain.novel

import kotlinx.serialization.Serializable

/**
 * Per-plugin record persisted alongside [NovelPreferences.installedPluginUrls]. Keyed in
 * [NovelPreferences.installedPluginMetadata] by the canonicalized plugin .js URL (same key the
 * installed-URLs Set uses).
 *
 * `iconUrl` is the absolute CDN URL resolved at build time by lnreader's plugins.min.json; the
 * plugin's own `plugin.icon` field is the relative authoring path and is not a usable URL.
 *
 * `version` is captured so future update detection can compare installed vs. registry versions
 * without re-shaping persistence.
 */
@Serializable
data class LnInstalledPluginMetadata(
    val pluginId: String,
    val iconUrl: String? = null,
    val version: String? = null,
)
