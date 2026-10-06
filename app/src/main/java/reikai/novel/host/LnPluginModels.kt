package reikai.novel.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.contentOrNull

/**
 * Kotlin mirrors of the lnreader Plugin types (see refs/lnreader-plugins/src/types/plugin.ts).
 * Only the surface the host needs is modelled; rare fields stay JsonElement so a stricter model
 * can be added later without touching the bridge.
 */

@Serializable
data class LnPluginInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val site: String? = null,
    val lang: String? = null,
    val iconUrl: String? = null,
    /** Raw plugin.filters schema. Pass-through; the host does not interpret it. */
    val filters: JsonObject? = null,
    /** Raw plugin.pluginSettings schema (per-plugin config). Pass-through; rendered by the settings UI. */
    val pluginSettings: JsonObject? = null,
    /** The headers the plugin asks its images be fetched with (lnreader's `imageRequestInit`). Raw, so a
     *  malformed value drops that header rather than failing the whole load. */
    val imageHeaders: JsonObject? = null,
    /**
     * The plugin reads lnreader's `showLatestNovels` option, so it can serve a Latest listing. Not a
     * field the plugin declares: the format has none, so the host derives it from the plugin source
     * and fills it in after decoding. Decoded as false, which is why the loader must set it.
     */
    val supportsLatest: Boolean = false,
    /** The plugin declares lnreader's `webStorageUtilized`, asking for the site's browser storage. */
    val webStorageUtilized: Boolean = false,
)

/**
 * Envelope produced by headless.js's `callMethod`. Either [value] (success) or [error] (failure)
 * will be present.
 */
@Serializable
data class LnCallResult(
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: String? = null,
)

@Serializable
data class NovelItem(
    val name: String,
    val path: String,
    val cover: String? = null,
)

@Serializable
data class ChapterItem(
    val name: String,
    val path: String,
    val releaseTime: String? = null,
    val chapterNumber: Double? = null,
    val page: String? = null,
    /** The chapter's translation group. A plugin may name several, joined as LNReader joins them. */
    @Serializable(with = ScanlatorSerializer::class)
    val scanlator: String? = null,
)

// LNReader's ChapterQueries rule for `string | string[]`: an array is `filter(Boolean).join(', ')`. Any
// other shape reads as no group, so an off-spec field cannot fail the whole chapter list.
private object ScanlatorSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> element
        is JsonArray -> JsonPrimitive(
            element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.ifEmpty { null } }.joinToString(", "),
        )
        else -> JsonPrimitive("")
    }
}

@Serializable
data class SourceNovel(
    val path: String,
    val name: String? = null,
    val cover: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val chapters: List<ChapterItem>? = null,
    /**
     * Number of pages the source spans for this novel. Most novels are single-page (default 1);
     * paged sources like Royal Road volumes return >1 so the update loop knows to fan out across
     * `parsePage` until it reaches `totalPages`. Mirrors lnreader's `SourceNovel.totalPages`.
     */
    val totalPages: Int = 1,
)

/**
 * Result of lnreader's `Plugin.parsePage(novelPath, page)`: just the chapter list for one page of a
 * paged source. Decoded separately from [SourceNovel] because the plugin returns only `{ chapters }`
 * (no path/metadata).
 */
@Serializable
data class SourcePage(
    val chapters: List<ChapterItem>? = null,
)
