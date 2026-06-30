package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import exh.metadata.metadata.NHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.NHENTAI_NET_SOURCE_ID
import exh.util.trimOrNull
import exh.util.urlImportFetchSearchMangaSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Built-in nhentai.net source (no extension needed), self-contained like the built-in E-Hentai.
 * Talks to nhentai's v2 JSON API directly; the old extension-backed API is gone, so the parsing
 * is shipped here. The legacy delegated [NHentai] wrapper stays for any installed nhentai
 * extension, but does not depend on this source.
 */
class NHentaiNet(private val context: Context) :
    HttpSource(),
    MetadataSource<NHentaiSearchMetadata, Response>,
    UrlImportableSource,
    NamespaceSource,
    PagePreviewSource {

    override val id = NHENTAI_NET_SOURCE_ID
    override val name = "NHentai.net"
    override val lang = "all"
    override val baseUrl = NHentaiSearchMetadata.BASE_URL
    override val supportsLatest = true

    override val metaClass = NHentaiSearchMetadata::class
    override fun newMetaInstance() = NHentaiSearchMetadata()

    // Throttle calls to the API host to stay well under nhentai's rate limits; the image CDNs
    // (i*.nhentai.net) are left unthrottled so reading stays fast.
    override val client = network.client.newBuilder()
        .rateLimitHost(NHentaiSearchMetadata.BASE_URL, 5)
        .build()

    private val sourcePreferences: SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", 0x0000)
    }

    private val preferredTitle: Int
        get() = when (sourcePreferences.getString(TITLE_PREF, "full")) {
            "full" -> NHentaiSearchMetadata.TITLE_TYPE_ENGLISH
            else -> NHentaiSearchMetadata.TITLE_TYPE_SHORT
        }

    // --- Browse / search ---

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/v2/galleries/popular", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/v2/galleries?page=$page&per_page=$PER_PAGE", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/api/v2/search?query=${Uri.encode(query.trim())}&sort=date&page=$page", headers)

    // /galleries/popular returns a bare array (no paging); /galleries and /search are paginated.
    override fun popularMangaParse(response: Response): MangasPage {
        val items = jsonParser.decodeFromString<List<JsonListItem>>(response.body.string())
        return MangasPage(items.map { it.toSManga() }, hasNextPage = false)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = paginatedParse(response)
    override fun searchMangaParse(response: Response): MangasPage = paginatedParse(response)

    private fun paginatedParse(response: Response): MangasPage {
        val parsed = jsonParser.decodeFromString<JsonPaginated>(response.body.string())
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(parsed.result.map { it.toSManga() }, hasNextPage = page < parsed.numPages)
    }

    private fun JsonListItem.toSManga() = SManga.create().apply {
        url = NHentaiSearchMetadata.nhIdToPath(id)
        title = englishTitle ?: japaneseTitle.orEmpty()
        thumbnail_url = thumbnail?.let { "$thumbServer/$it" }
    }

    // Resolve a pasted nhentai gallery URL via GalleryAdder; otherwise run a normal search.
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return urlImportFetchSearchMangaSuspend(context, query) {
            super<HttpSource>.getSearchManga(page, query, filters)
        }
    }

    // --- Details + chapters + pages ---

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(galleryApiUrl(manga.url), headers)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val response = client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
            parseToManga(manga, response)
        } else {
            manga
        }
        // An nhentai gallery is a single chapter; reading happens through the gallery's pages.
        val updatedChapters = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Chapter"
                    chapter_number = 1f
                },
            )
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override fun pageListRequest(chapter: SChapter): Request =
        GET(galleryApiUrl(chapter.url), headers)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (nhConfig == null) getNhConfig()
        return pageListParse(client.newCall(pageListRequest(chapter)).awaitSuccess())
    }

    override fun pageListParse(response: Response): List<Page> {
        val gallery = jsonParser.decodeFromString<JsonResponse>(response.body.string())
        return gallery.pages.mapIndexedNotNull { index, page ->
            page.path?.let { Page(index, imageUrl = "$imageServer/$it") }
        }
    }

    // --- Metadata ---

    override suspend fun parseIntoMetadata(metadata: NHentaiSearchMetadata, input: Response) {
        if (nhConfig == null) getNhConfig()
        val jsonResponse = jsonParser.decodeFromString<JsonResponse>(input.body.string())

        with(metadata) {
            nhId = jsonResponse.id
            uploadDate = jsonResponse.uploadDate
            favoritesCount = jsonResponse.numFavorites
            mediaId = jsonResponse.mediaId

            jsonResponse.title?.let { title ->
                japaneseTitle = title.japanese
                shortTitle = title.pretty
                englishTitle = title.english
            }

            preferredTitle = this@NHentaiNet.preferredTitle

            coverImageUrl =
                jsonResponse.cover?.path?.let { "$thumbServer/$it" }
                    ?: jsonResponse.thumbnail?.path?.let { "$thumbServer/$it" }

            pageImagePreviewUrls = jsonResponse.pages.mapNotNull { it.thumbnail }

            scanlator = jsonResponse.scanlator?.trimOrNull()

            tags.clear()
            jsonResponse.tags.filter {
                it.type != null && it.name != null
            }.mapTo(tags) {
                RaisedTag(
                    it.type!!,
                    it.name!!,
                    if (it.type == NHentaiSearchMetadata.NHENTAI_CATEGORIES_NAMESPACE) {
                        RaisedSearchMetadata.TAG_TYPE_VIRTUAL
                    } else {
                        NHentaiSearchMetadata.TAG_TYPE_DEFAULT
                    },
                )
            }
        }
    }

    // --- URL import ---

    override val matchingHosts = listOf(
        "nhentai.net",
    )

    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        if (uri.pathSegments.firstOrNull()?.lowercase() != "g") {
            return null
        }
        return "$baseUrl/g/${uri.pathSegments[1]}/"
    }

    // --- Page previews (the per-gallery thumbnail grid) ---

    override suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage {
        if (nhConfig == null) getNhConfig()
        val metadata = fetchOrLoadMetadata(manga.id()) {
            client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
        }
        return PagePreviewPage(
            page,
            metadata.pageImagePreviewUrls.mapIndexed { index, path ->
                PagePreviewInfo(index + 1, imageUrl = "$thumbServer/$path")
            },
            hasNextPage = false,
            pagePreviewPages = 1,
        )
    }

    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response {
        return client.newCachelessCallWithProgress(
            if (cacheControl != null) GET(page.imageUrl, cache = cacheControl) else GET(page.imageUrl),
            page,
        ).awaitSuccess()
    }

    // --- nhentai server config ---

    private var nhConfig: JsonConfig? = null

    private suspend fun getNhConfig() {
        try {
            val body = withIOContext { client.newCall(GET("$baseUrl/api/v2/config", headers)).awaitSuccess() }
                .use { it.body.string() }
            nhConfig = jsonParser.decodeFromString<JsonConfig>(body)
        } catch (_: Exception) {
            nhConfig = JsonConfig(
                (1..4).map { n -> "https://i$n.nhentai.net" },
                (1..4).map { n -> "https://t$n.nhentai.net" },
            )
        }
    }

    private val imageServer
        get() = nhConfig?.imageServers?.randomOrNull() ?: "https://i1.nhentai.net"

    private val thumbServer
        get() = nhConfig?.thumbServers?.randomOrNull() ?: "https://t1.nhentai.net"

    private fun galleryApiUrl(mangaUrl: String) =
        "$baseUrl/api/v2/galleries/${NHentaiSearchMetadata.nhUrlToId(mangaUrl)}"

    // --- v2 API DTOs ---

    @Serializable
    private data class JsonPaginated(
        val result: List<JsonListItem> = emptyList(),
        @SerialName("num_pages") val numPages: Int = 1,
    )

    @Serializable
    private data class JsonListItem(
        val id: Long,
        @SerialName("english_title") val englishTitle: String? = null,
        @SerialName("japanese_title") val japaneseTitle: String? = null,
        val thumbnail: String? = null,
    )

    @Serializable
    private data class JsonConfig(
        @SerialName("image_servers") val imageServers: List<String> = emptyList(),
        @SerialName("thumb_servers") val thumbServers: List<String> = emptyList(),
    )

    @Serializable
    private data class JsonResponse(
        val id: Long,
        @SerialName("media_id") val mediaId: String? = null,
        val title: JsonTitle? = null,
        val cover: JsonPage? = null,
        val thumbnail: JsonPage? = null,
        val scanlator: String? = null,
        @SerialName("upload_date") val uploadDate: Long? = null,
        val tags: List<JsonTag> = emptyList(),
        @SerialName("num_pages") val numPages: Int? = null,
        @SerialName("num_favorites") val numFavorites: Long? = null,
        val pages: List<JsonPage> = emptyList(),
    )

    @Serializable
    private data class JsonTitle(
        val english: String? = null,
        val japanese: String? = null,
        val pretty: String? = null,
    )

    @Serializable
    private data class JsonPage(
        val path: String? = null,
        val thumbnail: String? = null,
    )

    @Serializable
    private data class JsonTag(
        val type: String? = null,
        val name: String? = null,
    )

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
        private const val TITLE_PREF = "Display manga title as:"
        private const val PER_PAGE = 25
    }
}
