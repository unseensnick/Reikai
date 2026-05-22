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

    /** Icon URL from the registry. Null until phase 3 step 5 wires the install flow. */
    val icon: String?

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

    /** Fetch chapter HTML/text body. `chapterPath` is the source-relative path returned inside a
     *  [SourceNovel.chapters] entry. */
    suspend fun parseChapter(chapterPath: String): String
}
