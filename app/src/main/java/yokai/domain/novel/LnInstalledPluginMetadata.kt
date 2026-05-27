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
    /** Plugin language tag (e.g. `en`, `id`) from the registry index. lnreader plugin classes
     *  do NOT expose lang at runtime (only id/name/version/site/icon), so we persist the
     *  registry's value at install time and inject it back through bootstrap.js on load. */
    val lang: String? = null,
)
