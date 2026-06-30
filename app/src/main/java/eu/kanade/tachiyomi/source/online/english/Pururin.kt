package eu.kanade.tachiyomi.source.online.english

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
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
import eu.kanade.tachiyomi.util.asJsoup
import exh.metadata.metadata.PururinSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.PURURIN_SOURCE_ID
import exh.util.urlImportFetchSearchMangaSuspend
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

/**
 * Built-in pururin.me source (no extension needed), self-contained like the built-in E-Hentai.
 * Was a delegated wrapper that relied on an installed extension; the canonical pururin extension
 * isn't in Keiyoushi, so the parsing is shipped here.
 */
class Pururin(private val context: Context) :
    HttpSource(),
    MetadataSource<PururinSearchMetadata, Document>,
    UrlImportableSource,
    NamespaceSource {

    override val id = PURURIN_SOURCE_ID
    override val name = "Pururin"
    override val lang = "en"
    override val baseUrl = PururinSearchMetadata.BASE_URL
    override val supportsLatest = true

    override val metaClass = PururinSearchMetadata::class
    override fun newMetaInstance() = PururinSearchMetadata()

    // Throttle calls to pururin.me; the image host (i.pururin.me) is left unthrottled for reading.
    override val client = network.client.newBuilder()
        .rateLimitHost(PururinSearchMetadata.BASE_URL, 5)
        .build()

    // --- Browse / search ---

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=most-popular&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/browse?page=$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=${Uri.encode(query.trim())}&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = galleryListParse(response)
    override fun latestUpdatesParse(response: Response): MangasPage = galleryListParse(response)
    override fun searchMangaParse(response: Response): MangasPage = galleryListParse(response)

    private fun galleryListParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.card-gallery").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href").ifBlank { element.attr("href") })
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img.card-img-top")?.attr("abs:src")
            }
        }
        // Pururin lists a fixed page size; a full page implies another may follow.
        val hasNextPage = document.selectFirst("a[rel=next], .pagination a[aria-label=Next]") != null ||
            mangas.size >= PAGE_SIZE
        return MangasPage(mangas, hasNextPage)
    }

    // Support direct URL importing (paste a gallery URL or id:<n>); otherwise a normal search.
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedIdQuery = query.trim().removePrefix("id:")
        val newQuery = if ((trimmedIdQuery.toIntOrNull() ?: -1) >= 0) {
            "$baseUrl/gallery/$trimmedIdQuery/-"
        } else {
            query
        }
        return urlImportFetchSearchMangaSuspend(context, newQuery) {
            super<HttpSource>.getSearchManga(page, query, filters)
        }
    }

    // --- Details + chapters + pages ---

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val response = client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
            parseToManga(manga, response.asJsoup())
        } else {
            manga
        }
        // A Pururin gallery is a single chapter; reading happens through the gallery page.
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

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        // Cover is i.pururin.me/{folder}/cover.jpg; the reading pages share that folder as {n}.jpg.
        val coverSrc = document.selectFirst(".cover-wrapper img")?.absUrl("src").orEmpty()
        val folder = coverSrc.substringAfter("i.pururin.me/", "").substringBeforeLast("/", "")
        val pageCount = document.selectFirst("[itemprop=numberOfPages]")?.text()?.trim()?.toIntOrNull() ?: 0
        if (folder.isBlank() || pageCount == 0) return emptyList()
        return (1..pageCount).map { index ->
            Page(index - 1, imageUrl = "https://i.pururin.me/$folder/$index.jpg")
        }
    }

    // --- Metadata + URL import ---

    // pururin.me serves gallery details as microdata (itemprop=*) with tag links of the form
    // /browse/tags/{namespace}/{id}/{slug}, so the namespace is read straight off each link.
    override suspend fun parseIntoMetadata(metadata: PururinSearchMetadata, input: Document) {
        with(metadata) {
            // Reading link is /read/{id}/{page}/{slug}: a reliable source for id + slug.
            val readLink = input.selectFirst(".cover-wrapper a[href*=/read/]")?.attr("href")?.toUri()
            prId = input.selectFirst(".content-wrapper .title .id")?.text()?.removePrefix("G")?.trim()?.toIntOrNull()
                ?: readLink?.pathSegments?.getOrNull(1)?.toIntOrNull()
            prShortLink = readLink?.lastPathSegment

            title = input.selectFirst(".content-wrapper .title h1")?.text()
            altTitle = input.selectFirst(".content-wrapper .alt-title")?.text()

            thumbnailUrl = input.selectFirst(".cover-wrapper img")?.absUrl("src")

            pages = input.selectFirst("[itemprop=numberOfPages]")?.text()?.trim()?.toIntOrNull()
            ratingCount = input.selectFirst("[itemprop=ratingCount]")
                ?.let { it.attr("content").ifBlank { it.text() } }?.trim()?.toIntOrNull()
            averageRating = input.selectFirst("[itemprop=ratingValue]")
                ?.let { it.attr("content").ifBlank { it.text() } }?.trim()?.toDoubleOrNull()

            tags.clear()
            input.select(".content-wrapper a[href*=/browse/tags/]").forEach { link ->
                val segments = link.attr("href").toUri().pathSegments
                val namespace = segments.getOrNull(segments.indexOf("tags") + 1)
                val name = link.text().trim()
                if (namespace.isNullOrBlank() || name.isBlank()) return@forEach
                tags += RaisedTag(
                    namespace,
                    name,
                    if (namespace != PururinSearchMetadata.TAG_NAMESPACE_CATEGORY) {
                        PururinSearchMetadata.TAG_TYPE_DEFAULT
                    } else {
                        RaisedSearchMetadata.TAG_TYPE_VIRTUAL
                    },
                )
            }
        }
    }

    override val matchingHosts = listOf(
        "pururin.me",
    )

    override suspend fun mapUrlToMangaUrl(uri: Uri): String {
        return "${PururinSearchMetadata.BASE_URL}/gallery/${uri.pathSegments.getOrNull(1)}/${uri.lastPathSegment}"
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}
