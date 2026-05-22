package yokai.domain.novel

import eu.kanade.tachiyomi.core.preference.PreferenceStore

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
}
