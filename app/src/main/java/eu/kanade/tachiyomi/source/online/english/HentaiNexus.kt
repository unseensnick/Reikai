package eu.kanade.tachiyomi.source.online.english

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.network.GET
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
import exh.metadata.metadata.HentaiNexusSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import exh.source.layeredMangaUpdate
import exh.util.urlImportFetchSearchMangaSuspend
import okhttp3.CacheControl
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class HentaiNexus(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<HentaiNexusSearchMetadata, Document>,
    UrlImportableSource,
    NamespaceSource,
    PagePreviewSource {
    override val metaClass = HentaiNexusSearchMetadata::class
    override fun newMetaInstance() = HentaiNexusSearchMetadata()

    // The site's search reads `key:value`, quoting a value of several words, under singular keys.
    override fun tagSearchQuery(namespace: String, tag: String): String {
        val key = if (namespace == GallerySiteSearchMetadata.TAGS_NAMESPACE) "tag" else namespace
        return if (tag.contains(' ')) "$key:\"$tag\"" else "$key:$tag"
    }
    override val lang = "en"

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = layeredMangaUpdate(manga, chapters, fetchDetails, fetchChapters) { base, response ->
        parseToManga(base, response.asJsoup())
    }

    // resolve a pasted gallery URL via GalleryAdder; otherwise run a normal search.
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return urlImportFetchSearchMangaSuspend(context, query) {
            super<DelegatedHttpSource>.getSearchManga(page, query, filters)
        }
    }

    override suspend fun parseIntoMetadata(metadata: HentaiNexusSearchMetadata, input: Document) {
        val details = input.selectFirst(".view-page-details")

        // The details table pairs a `td.viewcolumn` label with the value cell after it.
        fun cell(label: String) = details?.select("td.viewcolumn")
            ?.firstOrNull { it.text() == label }
            ?.nextElementSibling()

        with(metadata) {
            hnId = GALLERY_ID_REGEX.find(input.location())?.groupValues?.get(1)?.toIntOrNull()
            title = input.selectFirst("h1.title")?.text()
            thumbnailUrl = input.selectFirst("figure.image img")?.absUrl("src")
            publishedDate = cell("Published")?.text()?.let {
                runCatching {
                    LocalDate.parse(it, DATE_FORMAT).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                }.getOrNull()
            }
            pageCount = cell("Pages")?.text()?.toIntOrNull()
            favorites = cell("Favorites")?.text()?.replace(",", "")?.toIntOrNull()
            pagePreviewUrls = input.select("a[href^=/read/] .card-image img").map { it.absUrl("src") }

            tags.clear()
            NAMESPACES.forEach { (label, namespace) ->
                cell(label)?.select("a")?.forEach {
                    tags += RaisedTag(namespace, it.ownText().trim(), GallerySiteSearchMetadata.TAG_TYPE_DEFAULT)
                }
            }
            cell("Tags")?.select("span.tag a")?.forEach {
                tags += RaisedTag(
                    GallerySiteSearchMetadata.TAGS_NAMESPACE,
                    it.text().replace(TAG_COUNT_REGEX, ""),
                    GallerySiteSearchMetadata.TAG_TYPE_DEFAULT,
                )
            }
        }
    }

    override val matchingHosts = listOf(
        "hentainexus.com",
        "www.hentainexus.com",
    )

    // A gallery's page and its reader both carry the gallery id: /view/<id> and /read/<id>.
    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.firstOrNull() !in listOf("view", "read")) return null
        return segments.getOrNull(1)?.toIntOrNull()?.let { "/view/$it" }
    }

    override suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage {
        val metadata = fetchOrLoadMetadata(manga.id()) {
            client.newCall(mangaDetailsRequest(manga)).awaitSuccess().asJsoup()
        }
        return PagePreviewPage(
            page,
            metadata.pagePreviewUrls.mapIndexed { index, url -> PagePreviewInfo(index + 1, imageUrl = url) },
            false,
            1,
        )
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

    companion object {
        // Details-table label to the namespace its links are stored under.
        private val NAMESPACES = listOf(
            "Artist" to GallerySiteSearchMetadata.ARTIST_NAMESPACE,
            "Author" to "author",
            "Circle" to "circle",
            "Event" to "event",
            "Magazine" to "magazine",
            "Parody" to GallerySiteSearchMetadata.PARODY_NAMESPACE,
            "Publisher" to "publisher",
        )
        private val TAG_COUNT_REGEX = Regex("""\s*\([\d,]+\)$""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US)
        private val GALLERY_ID_REGEX = Regex("""/view/(\d+)""")
    }
}
