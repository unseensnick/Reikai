package yokai.novel.source

import kotlinx.serialization.json.JsonObject
import yokai.novel.host.NovelItem
import yokai.novel.host.SourceNovel

/**
 * Production contract for a light-novel source. Method names track lnreader's `Plugin` interface
 * for grep-ability across the two codebases; everything is suspending and content-shaped
 * (`NovelItem`, `SourceNovel`, chapter text as `String`) rather than `SManga` / `SChapter` / `Page`
 * the manga side uses.
 *
 * Implementations: see [LnPluginSource] (the only one in Slice A — wraps a JS plugin in an
 * [LnPluginHost]). Future implementations could include a Local file source or a Kotlin-native LN
 * source if we ever ship one.
 */
interface NovelSource {

    /** Source id, matches the upstream lnreader registry's `id` field (e.g. `novelbin`). */
    val id: String

    val name: String
    val version: String
    val site: String

    /** lnreader plugin language tag (e.g. `en`, `id`, `zh`). Empty when the plugin doesn't
     *  declare one. Drives the Language section grouping on the Browse sources list. */
    val lang: String

    /**
     * Absolute CDN URL for the source's icon, resolved at install time from the lnreader registry's
     * `iconUrl` field. Null for legacy installs whose lazy backfill hasn't matched a repo yet, or
     * for direct-URL-paste installs that bypass the registry.
     */
    val iconUrl: String?

    /**
     * Raw `plugin.filters` schema. The host does not interpret this; a future filter-renderer in
     * Compose will read the shape (Picker / Switch / CheckboxGroup / ExcludableCheckboxGroup /
     * TextInput) and emit values into [popularNovels]'s `optionsJson`.
     */
    val filters: JsonObject?

    /**
     * @param optionsJson lnreader `PopularNovelsOptions` shape, JSON-encoded. Slice A doesn't
     * model this structurally; callers either pass `"{}"` (sources whose `popularNovels` falls
     * back to `this.filters`) or hand-build a JSON string matching the plugin's filter defaults.
     */
    suspend fun popularNovels(page: Int, optionsJson: String = "{}"): List<NovelItem>

    suspend fun searchNovels(query: String, page: Int): List<NovelItem>

    /** Fetch a novel's details + chapter list. `novelPath` is the source-relative path returned
     *  inside a [NovelItem]. */
    suspend fun parseNovel(novelPath: String): SourceNovel

    /**
     * Fetch the chapter list for a single page of a paged-novel source (Royal Road volumes, etc.).
     * Plugins whose chapter lists span multiple endpoints override this; the default returns null,
     * meaning "this source is single-page, just call [parseNovel]". The update job uses the
     * non-null vs null result to decide whether to fan out across pages. Mirrors lnreader's
     * `Plugin.parsePage` (refs/lnreader-main/src/services/updates/index.ts:222-233).
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
}
