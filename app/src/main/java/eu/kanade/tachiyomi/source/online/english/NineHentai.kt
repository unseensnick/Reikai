package eu.kanade.tachiyomi.source.online.english

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.metadata.metadata.GallerySiteSearchMetadata
import exh.metadata.metadata.NineHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import exh.source.layeredMangaUpdate
import exh.util.urlImportFetchSearchMangaSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import java.util.Calendar

class NineHentai(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<NineHentaiSearchMetadata, NineHentai.GalleryInput>,
    UrlImportableSource,
    NamespaceSource,
    PagePreviewSource {
    override val metaClass = NineHentaiSearchMetadata::class
    override fun newMetaInstance() = NineHentaiSearchMetadata()

    // The site searches tags by id through its own filters, so a chip searches the tag's name.
    override fun tagSearchQuery(namespace: String, tag: String) = tag
    override val lang = "en"

    class GalleryInput(val document: Document, val book: Book?)

    @Serializable
    class BookResponse(val results: Book)

    @Serializable
    class Book(
        val id: Int,
        @SerialName("alt_title") val altTitle: String? = null,
        @SerialName("total_page") val totalPage: Int? = null,
        @SerialName("total_favorite") val totalFavorite: Int? = null,
        @SerialName("total_view") val totalView: Int? = null,
        @SerialName("total_download") val totalDownload: Int? = null,
    )

    // The extension's API image host is dead for old galleries; the page's own host serves them.
    private val imageBaseUrl get() = "https://i.${baseUrl.toHttpUrl().host}/images"

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = layeredMangaUpdate(manga, chapters, fetchDetails, fetchChapters) { base, response ->
        parseToManga(base, galleryInput(manga, response))
    }

    // resolve a pasted gallery URL via GalleryAdder; otherwise run a normal search.
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return urlImportFetchSearchMangaSuspend(context, query) {
            super<DelegatedHttpSource>.getSearchManga(page, query, filters)
        }
    }

    // The favorite, view and download counts only exist in the API; the gallery stands without them.
    private suspend fun galleryInput(manga: SManga, response: Response): GalleryInput {
        val document = response.asJsoup()
        val book = galleryId(manga.url)?.let { id ->
            runCatchingCancellable {
                val request = POST("$baseUrl/api/getBookByID", headers, """{"id":$id}""".toRequestBody(JSON_MEDIA_TYPE))
                val body = client.newCall(request).awaitSuccess().body.string()
                jsonParser.decodeFromString<BookResponse>(body).results
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Failed to fetch the gallery stats for $id" }
            }.getOrNull()
        }
        return GalleryInput(document, book)
    }

    override suspend fun parseIntoMetadata(metadata: NineHentaiSearchMetadata, input: GalleryInput) {
        val info = input.document.selectFirst("#info")

        with(metadata) {
            nhId = galleryId(input.document.location()) ?: input.book?.id
            thumbnailUrl = nhId?.let { "$imageBaseUrl/$it/cover.jpg" }
            title = info?.selectFirst("h1")?.text()
            altTitle = info?.selectFirst("h2")?.text()?.ifBlank { null } ?: input.book?.altTitle
            pageCount = info?.select("> div")
                ?.firstOrNull { it.ownText().endsWith("pages") }
                ?.ownText()
                ?.substringBefore(" ")
                ?.toIntOrNull()
                ?: input.book?.totalPage
            uploadDate = info?.selectFirst("div time")?.text()?.let(::parseRelativeDate)
            favorites = input.book?.totalFavorite
            views = input.book?.totalView
            downloads = input.book?.totalDownload

            tags.clear()
            info?.select("#tags .tag-container")?.forEach { container ->
                val label = container.ownText().trim().removeSuffix(":").lowercase()
                // The site labels the plain tags "Tag"; the other labels match the shared namespaces.
                val namespace = if (label == "tag") GallerySiteSearchMetadata.TAGS_NAMESPACE else label
                container.select("a.tag").forEach {
                    tags += RaisedTag(namespace, it.text(), GallerySiteSearchMetadata.TAG_TYPE_DEFAULT)
                }
            }
        }
    }

    override val matchingHosts = listOf(
        "9hentai.so",
        "www.9hentai.so",
    )

    // The path rather than SY's whole link, so a query carrying "/g/<id>" names no gallery.
    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        return uri.path?.let(::galleryId)?.let { "/g/$it" }
    }

    override suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage {
        val metadata = fetchOrLoadMetadata(manga.id()) {
            galleryInput(manga, client.newCall(mangaDetailsRequest(manga)).awaitSuccess())
        }
        val id = metadata.nhId
        val previews = if (id == null) {
            emptyList()
        } else {
            (1..(metadata.pageCount ?: 0)).map {
                PagePreviewInfo(it, imageUrl = "$imageBaseUrl/$id/preview/${it}t.jpg")
            }
        }
        return PagePreviewPage(page, previews, false, 1)
    }

    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response {
        return client.newCachelessCallWithProgress(
            if (cacheControl != null) {
                GET(page.imageUrl, cache = cacheControl)
            } else {
                GET(page.imageUrl)
            },
            page,
        ).awaitSuccess()
    }

    private fun galleryId(url: String): Int? = GALLERY_ID_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull()

    // The site shows only "3 days ago" style ages; the extension reads its chapter date the same way.
    private fun parseRelativeDate(date: String): Long? {
        val split = date.split(" ")
        val value = split.getOrNull(0)?.toIntOrNull() ?: return null
        val field = when (split.getOrNull(1)?.removeSuffix("s")) {
            "sec", "second" -> Calendar.SECOND
            "min", "minute" -> Calendar.MINUTE
            "hour" -> Calendar.HOUR_OF_DAY
            "day" -> Calendar.DATE
            "week" -> Calendar.WEEK_OF_YEAR
            "month" -> Calendar.MONTH
            "year" -> Calendar.YEAR
            else -> return null
        }
        return Calendar.getInstance().apply { add(field, -value) }.timeInMillis
    }

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val GALLERY_ID_REGEX = Regex("""/g/(\d+)""")
    }
}
