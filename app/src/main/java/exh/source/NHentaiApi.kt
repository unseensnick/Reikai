package exh.source

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import exh.metadata.metadata.NHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.util.trimOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext

// nhentai's v2 API, read the same way by the built-in NHentaiNet and the extension wrapper NHentai.

internal val nhJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class NhGallery(
    val id: Long,
    @SerialName("media_id") val mediaId: String? = null,
    val title: NhTitle? = null,
    val cover: NhPage? = null,
    val thumbnail: NhPage? = null,
    val scanlator: String? = null,
    @SerialName("upload_date") val uploadDate: Long? = null,
    val tags: List<NhTag> = emptyList(),
    @SerialName("num_favorites") val numFavorites: Long? = null,
    val pages: List<NhPage> = emptyList(),
)

@Serializable
internal data class NhTitle(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
internal data class NhPage(
    val path: String? = null,
    val thumbnail: String? = null,
)

@Serializable
internal data class NhTag(
    val type: String? = null,
    val name: String? = null,
)

@Serializable
private data class NhConfig(
    @SerialName("image_servers") val imageServers: List<String> = emptyList(),
    @SerialName("thumb_servers") val thumbServers: List<String> = emptyList(),
)

/** The image and thumbnail hosts nhentai names in its config, fetched once per source. */
internal class NhServers {
    @Volatile
    private var config: NhConfig? = null

    suspend fun ensure(client: OkHttpClient, headers: Headers) {
        if (config != null) return
        config = try {
            val body = withIOContext {
                client.newCall(GET("${NHentaiSearchMetadata.BASE_URL}/api/v2/config", headers)).awaitSuccess()
            }.use { it.body.string() }
            nhJson.decodeFromString<NhConfig>(body)
        } catch (_: Exception) {
            NhConfig(
                (1..4).map { n -> "https://i$n.nhentai.net" },
                (1..4).map { n -> "https://t$n.nhentai.net" },
            )
        }
    }

    val imageServer: String
        get() = config?.imageServers?.randomOrNull() ?: "https://i1.nhentai.net"

    val thumbServer: String
        get() = config?.thumbServers?.randomOrNull() ?: "https://t1.nhentai.net"
}

internal fun NHentaiSearchMetadata.fillFrom(gallery: NhGallery, thumbServer: String, preferredTitle: Int) {
    nhId = gallery.id
    uploadDate = gallery.uploadDate
    favoritesCount = gallery.numFavorites
    mediaId = gallery.mediaId

    gallery.title?.let { title ->
        japaneseTitle = title.japanese
        shortTitle = title.pretty
        englishTitle = title.english
    }

    this.preferredTitle = preferredTitle

    coverImageUrl = (gallery.cover?.path ?: gallery.thumbnail?.path)?.let { "$thumbServer/$it" }

    pageImagePreviewUrls = gallery.pages.mapNotNull { it.thumbnail }

    scanlator = gallery.scanlator?.trimOrNull()

    tags.clear()
    gallery.tags.mapNotNullTo(tags) { tag ->
        val type = tag.type ?: return@mapNotNullTo null
        val name = tag.name ?: return@mapNotNullTo null
        val tagType = if (type == NHentaiSearchMetadata.NHENTAI_CATEGORIES_NAMESPACE) {
            RaisedSearchMetadata.TAG_TYPE_VIRTUAL
        } else {
            NHentaiSearchMetadata.TAG_TYPE_DEFAULT
        }
        RaisedTag(type, name, tagType)
    }
}

/** The title shown for a gallery, from the source's "Display manga title as:" setting. */
internal fun nhPreferredTitle(prefs: SharedPreferences): Int =
    when (prefs.getString("Display manga title as:", "full")) {
        "full" -> NHentaiSearchMetadata.TITLE_TYPE_ENGLISH
        else -> NHentaiSearchMetadata.TITLE_TYPE_SHORT
    }

/** nhentai shows every page thumbnail on one preview page. */
internal fun nhPagePreviews(page: Int, thumbnailPaths: List<String>, thumbServer: String) = PagePreviewPage(
    page,
    thumbnailPaths.mapIndexed { index, path -> PagePreviewInfo(index + 1, imageUrl = "$thumbServer/$path") },
    hasNextPage = false,
    pagePreviewPages = 1,
)
