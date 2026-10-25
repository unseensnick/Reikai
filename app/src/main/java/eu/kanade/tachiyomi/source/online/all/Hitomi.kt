package eu.kanade.tachiyomi.source.online.all

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
import exh.metadata.metadata.GallerySiteSearchMetadata
import exh.metadata.metadata.HitomiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import exh.source.layeredMangaUpdate
import exh.util.urlImportFetchSearchMangaSuspend
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.Response
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class Hitomi(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<HitomiSearchMetadata, String>,
    UrlImportableSource,
    NamespaceSource,
    PagePreviewSource {
    override val metaClass = HitomiSearchMetadata::class
    override fun newMetaInstance() = HitomiSearchMetadata()

    // The site's search reads `key:value` with underscores for spaces, under its own keys.
    override fun tagSearchQuery(namespace: String, tag: String): String {
        val key = SEARCH_KEYS[namespace] ?: namespace
        return "$key:${tag.lowercase().replace(' ', '_')}"
    }
    override val lang = delegate.lang

    // The details request is the extension's own galleries script, so the extension's fields stand.
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = layeredMangaUpdate(manga, chapters, fetchDetails, fetchChapters) { base, response ->
        parseToManga(base, response.body.string())
    }

    // resolve a pasted gallery URL via GalleryAdder; otherwise run a normal search.
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return urlImportFetchSearchMangaSuspend(context, query) {
            super<DelegatedHttpSource>.getSearchManga(page, query, filters)
        }
    }

    @Serializable
    class Gallery(
        val id: JsonPrimitive? = null,
        val galleryurl: String? = null,
        val title: String? = null,
        val date: String? = null,
        val type: String? = null,
        val language: String? = null,
        val tags: List<Tag>? = null,
        val artists: List<Artist>? = null,
        val groups: List<Group>? = null,
        val characters: List<Character>? = null,
        val parodys: List<Parody>? = null,
        val files: List<ImageFile>? = null,
    )

    // The site writes the flags as "1", 1 or "", so they are read as raw primitives.
    @Serializable
    class Tag(val tag: String = "", val female: JsonPrimitive? = null, val male: JsonPrimitive? = null)

    @Serializable
    class Artist(val artist: String = "")

    @Serializable
    class Group(val group: String = "")

    @Serializable
    class Character(val character: String = "")

    @Serializable
    class Parody(val parody: String = "")

    @Serializable
    class ImageFile(val hash: String = "", val name: String = "")

    override suspend fun parseIntoMetadata(metadata: HitomiSearchMetadata, input: String) {
        val gallery = parseGallery(input)

        with(metadata) {
            hlId = gallery.id?.content?.toIntOrNull() ?: gallery.galleryurl?.let(::galleryId)
            title = gallery.title
            galleryType = gallery.type
            language = gallery.language
            uploadDate = gallery.date?.let { runCatching { OffsetDateTime.parse(it, DATE_FORMAT) }.getOrNull() }
                ?.toInstant()
                ?.toEpochMilli()
            images = gallery.files.orEmpty()
                .filter { it.hash.isNotBlank() }
                .map { HitomiSearchMetadata.Image(it.hash, it.name.endsWith(".gif") || it.name.endsWith(".webp")) }
            thumbnailUrl = images.firstOrNull()?.let(::thumbnailUrl)

            tags.clear()
            fun add(namespace: String, names: List<String>?) = names.orEmpty().filter(String::isNotBlank).forEach {
                tags += RaisedTag(namespace, it, GallerySiteSearchMetadata.TAG_TYPE_DEFAULT)
            }
            add(GallerySiteSearchMetadata.ARTIST_NAMESPACE, gallery.artists?.map { it.artist })
            add(GallerySiteSearchMetadata.GROUP_NAMESPACE, gallery.groups?.map { it.group })
            add(GallerySiteSearchMetadata.PARODY_NAMESPACE, gallery.parodys?.map { it.parody })
            add(GallerySiteSearchMetadata.CHARACTER_NAMESPACE, gallery.characters?.map { it.character })
            gallery.tags.orEmpty().groupBy { tagNamespace(it) }.forEach { (namespace, group) ->
                add(namespace, group.map { it.tag })
            }
            add(GallerySiteSearchMetadata.CATEGORY_NAMESPACE, listOfNotNull(gallery.type))
            add(GallerySiteSearchMetadata.LANGUAGE_NAMESPACE, listOfNotNull(gallery.language))
        }
    }

    override val matchingHosts = listOf(
        "hitomi.la",
        "www.hitomi.la",
    )

    // GalleryAdder keys the entry on this url, so it must be the gallery's own: a reader link has
    // no slug, and the extension reads the id after the url's last dash.
    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        val id = uri.path?.let(::galleryId) ?: return null
        return parseGallery(client.newCall(galleryRequest(id)).awaitSuccess().body.string()).galleryurl
    }

    override suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage {
        val metadata = fetchOrLoadMetadata(manga.id()) {
            client.newCall(mangaDetailsRequest(manga)).awaitSuccess().body.string()
        }
        val previews = metadata.images.mapIndexed { index, image ->
            PagePreviewInfo(index + 1, imageUrl = thumbnailUrl(image))
        }
        return PagePreviewPage(page, previews, false, 1)
    }

    // The url is the extension's loopback address, which its own client's interceptor resolves.
    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response {
        return client.newCachelessCallWithProgress(
            if (cacheControl != null) {
                GET(page.imageUrl, headers, cache = cacheControl)
            } else {
                GET(page.imageUrl, headers)
            },
            page,
        ).awaitSuccess()
    }

    // Asks the extension for the request, so its api host stays the extension's to change.
    private fun galleryRequest(id: Int) = mangaDetailsRequest(SManga.create().apply { url = "/galleries/-$id.html" })

    private fun parseGallery(body: String): Gallery =
        jsonParser.decodeFromString(body.substringAfter("var galleryinfo = ").trim().removeSuffix(";"))

    private fun galleryId(path: String): Int? = GALLERY_ID_REGEX.find(path)?.groupValues?.get(1)?.toIntOrNull()

    private fun tagNamespace(tag: Tag) = when {
        tag.female?.content == "1" -> GallerySiteSearchMetadata.FEMALE_NAMESPACE
        tag.male?.content == "1" -> GallerySiteSearchMetadata.MALE_NAMESPACE
        else -> GallerySiteSearchMetadata.TAGS_NAMESPACE
    }

    // The same loopback shape the extension gives its covers; see its imageUrlInterceptor.
    private fun thumbnailUrl(image: HitomiSearchMetadata.Image): String = HttpUrl.Builder()
        .scheme("https")
        .host(IMAGE_LOOPBACK_HOST)
        .addQueryParameter("is_thumbnail", "true")
        .addQueryParameter("is_gif", image.isGif.toString())
        .fragment(image.hash)
        .build()
        .toString()

    companion object {
        private const val IMAGE_LOOPBACK_HOST = "127.0.0.1"
        private val GALLERY_ID_REGEX = Regex("""(\d+)\.html$""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssX")
        private val jsonParser = Json { ignoreUnknownKeys = true }

        private val SEARCH_KEYS = mapOf(
            GallerySiteSearchMetadata.TAGS_NAMESPACE to "tag",
            GallerySiteSearchMetadata.PARODY_NAMESPACE to "series",
            GallerySiteSearchMetadata.CATEGORY_NAMESPACE to "type",
        )
    }
}
