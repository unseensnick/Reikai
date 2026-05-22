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

    // Reader rendering preferences. Stored globally for the spike; future polish may add
    // per-novel overrides if needed.
    fun readerFontSize() = preferenceStore.getInt("ln_reader_font_size_sp", 16)
    fun readerLineSpacing() = preferenceStore.getFloat("ln_reader_line_spacing", 1.5f)
    /** 0 = follow system, 1 = light, 2 = dark. */
    fun readerTheme() = preferenceStore.getInt("ln_reader_theme", 0)
}
