package reikai.novel.source

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel

/**
 * Contract for a light-novel source. Method names track lnreader's `Plugin` interface for
 * grep-ability across the two codebases; everything is suspending and content-shaped (`NovelItem`,
 * `SourceNovel`, chapter text as `String`) rather than the `SManga` / `SChapter` / `Page` the manga
 * side uses.
 *
 * The only implementation is [LnPluginSource] (a JS plugin running in an [reikai.novel.host.LnPluginHost]).
 */
interface NovelSource {

    /** Source id, matches the upstream lnreader registry's `id` field (e.g. `novelbin`). */
    val id: String

    val name: String
    val version: String
    val site: String

    /** lnreader plugin language tag (e.g. `en`, `id`, `zh`). Empty when the plugin doesn't declare
     *  one. Drives the Language section grouping on the Browse sources list. */
    val lang: String

    /**
     * Absolute CDN URL for the source's icon, resolved at install time from the lnreader registry's
     * `iconUrl` field. Null for installs whose lazy backfill hasn't matched a repo yet, or for
     * direct-URL-paste installs that bypass the registry.
     */
    val iconUrl: String?

    /**
     * Raw `plugin.filters` schema. The host does not interpret this; a future filter renderer reads
     * the shape (Picker / Switch / CheckboxGroup / ExcludableCheckboxGroup / TextInput) and emits
     * values into [popularNovels]'s `optionsJson`.
     */
    val filters: JsonObject?

    /**
     * Raw `plugin.pluginSettings` schema (per-plugin config: login, base URL, content toggles). Null
     * when the source declares none. The settings UI renders it and persists values through
     * [setSetting]; the plugin reads them back via `@libs/storage`.
     */
    val pluginSettings: JsonObject? get() = null

    /** Read a saved per-plugin setting value (the `storage:` scope plugins use). Null if unset. */
    suspend fun getSetting(key: String): JsonElement? = null

    /** Persist a per-plugin setting value (null clears it). No-op for sources without settings. */
    fun setSetting(key: String, value: JsonElement?) {}

    /**
     * @param optionsJson lnreader `PopularNovelsOptions` shape, JSON-encoded. Callers either pass
     * `"{}"` (sources whose `popularNovels` falls back to `this.filters`) or hand-build a JSON string
     * matching the plugin's filter defaults.
     */
    suspend fun popularNovels(page: Int, optionsJson: String = "{}"): List<NovelItem>

    suspend fun searchNovels(query: String, page: Int): List<NovelItem>

    /** Fetch a novel's details + chapter list. `novelPath` is the source-relative path returned
     *  inside a [NovelItem]. */
    suspend fun parseNovel(novelPath: String): SourceNovel

    /**
     * Fetch the chapter list for a single page of a paged-novel source (Royal Road volumes, etc.).
     * Plugins whose chapter lists span multiple endpoints override this; the default returns null,
     * meaning "this source is single-page, just call [parseNovel]". Mirrors lnreader's
     * `Plugin.parsePage`.
     */
    suspend fun parsePage(novelPath: String, page: String): SourceNovel? = null

    /** Fetch chapter HTML/text body. `chapterPath` is the source-relative path returned inside a
     *  [SourceNovel.chapters] entry. */
    suspend fun parseChapter(chapterPath: String): String

    /**
     * Resolve a source-relative [path] to its absolute web URL via the plugin's optional lnreader
     * `resolveUrl`. Returns null when the plugin doesn't implement it (only some do), so callers
     * fall back to [site] (the source homepage).
     */
    suspend fun resolveUrl(path: String, isNovel: Boolean): String? = null

    /**
     * Full browser URL for a source-relative [path] (a novel or chapter), for opening in WebView or
     * sharing: the path itself if it is already absolute, else [site] + path. Mirrors lnreader's
     * `resolveUrl` service fallback (`plugin.site + path`); the [resolveUrl] plugin override is left
     * for a future caller since almost no plugins implement it.
     */
    fun webUrl(path: String): String = if (path.startsWith("http")) path else site + path
}
