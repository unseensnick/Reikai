package yokai.novel.host

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Kotlin mirrors of the lnreader Plugin types (see refs/lnreader-main/src/plugins/types/).
 * Only the surface the spike's probe screen reads is modelled; rare fields stay JsonElement so
 * a stricter Phase-2 model can be added without touching the bridge.
 */

@Serializable
data class LnPluginInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val site: String? = null,
    val icon: String? = null,
)

/**
 * Envelope produced by bootstrap.js's `callMethod`. Either [value] (success) or [error] (failure)
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
)

@Serializable
data class ChapterContent(
    @SerialName("chapterText") val chapterText: String,
)
