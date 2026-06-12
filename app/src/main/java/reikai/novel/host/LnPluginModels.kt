package reikai.novel.host

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
)

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

@Serializable
data class ChapterContent(
    @SerialName("chapterText") val chapterText: String,
)
