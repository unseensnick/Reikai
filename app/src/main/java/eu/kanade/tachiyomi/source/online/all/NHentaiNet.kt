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
import exh.source.NHENTAI_NET_SOURCE_ID
import exh.source.NhGallery
import exh.source.NhServers
import exh.source.fillFrom
import exh.source.nhJson
import exh.source.nhPagePreviews
import exh.source.nhPreferredTitle
import exh.source.singleChapterGalleryUpdate
import exh.util.SourceTagsUtil
import exh.util.urlImportFetchSearchMangaSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.Response

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

    override fun tagSearchQuery(namespace: String, tag: String) = SourceTagsUtil.wrapTagNHentai(namespace, tag)

    // Throttle calls to the API host to stay well under nhentai's rate limits; the image CDNs
    // (i*.nhentai.net) are left unthrottled so reading stays fast.
    override val client = network.client.newBuilder()
        .rateLimitHost(NHentaiSearchMetadata.BASE_URL, 5)
        .build()

    private val sourcePreferences: SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", 0x0000)
    }

    private val servers = NhServers()

    // --- Browse / search ---

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/v2/galleries/popular", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/v2/galleries?page=$page&per_page=$PER_PAGE", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/api/v2/search?query=${Uri.encode(query.trim())}&sort=date&page=$page", headers)

    // /galleries/popular returns a bare array (no paging); /galleries and /search are paginated.
    override fun popularMangaParse(response: Response): MangasPage {
        val items = nhJson.decodeFromString<List<JsonListItem>>(response.body.string())
        return MangasPage(items.map { it.toSManga() }, hasNextPage = false)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = paginatedParse(response)
    override fun searchMangaParse(response: Response): MangasPage = paginatedParse(response)

    private fun paginatedParse(response: Response): MangasPage {
        val parsed = nhJson.decodeFromString<JsonPaginated>(response.body.string())
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(parsed.result.map { it.toSManga() }, hasNextPage = page < parsed.numPages)
    }

    private fun JsonListItem.toSManga() = SManga.create().apply {
        url = NHentaiSearchMetadata.nhIdToPath(id)
        title = englishTitle ?: japaneseTitle.orEmpty()
        thumbnail_url = thumbnail?.let { "${servers.thumbServer}/$it" }
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
    ): SMangaUpdate = singleChapterGalleryUpdate(manga, chapters, fetchDetails, fetchChapters) {
        parseToManga(manga, client.newCall(mangaDetailsRequest(manga)).awaitSuccess())
    }

    override fun pageListRequest(chapter: SChapter): Request =
        GET(galleryApiUrl(chapter.url), headers)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        servers.ensure(client, headers)
        return pageListParse(client.newCall(pageListRequest(chapter)).awaitSuccess())
    }

    override fun pageListParse(response: Response): List<Page> {
        val gallery = nhJson.decodeFromString<NhGallery>(response.body.string())
        return gallery.pages.mapIndexedNotNull { index, page ->
            page.path?.let { Page(index, imageUrl = "${servers.imageServer}/$it") }
        }
    }

    // --- Metadata ---

    override suspend fun parseIntoMetadata(metadata: NHentaiSearchMetadata, input: Response) {
        servers.ensure(client, headers)
        val gallery = nhJson.decodeFromString<NhGallery>(input.body.string())
        metadata.fillFrom(gallery, servers.thumbServer, nhPreferredTitle(sourcePreferences))
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
        servers.ensure(client, headers)
        val metadata = fetchOrLoadMetadata(manga.id()) {
            client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
        }
        return nhPagePreviews(page, metadata.pageImagePreviewUrls, servers.thumbServer)
    }

    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response {
        return client.newCachelessCallWithProgress(
            if (cacheControl != null) GET(page.imageUrl, cache = cacheControl) else GET(page.imageUrl),
            page,
        ).awaitSuccess()
    }

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

    companion object {
        private const val PER_PAGE = 25
    }
}
