package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import android.content.SharedPreferences
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
import exh.metadata.metadata.NHentaiSearchMetadata
import exh.source.DelegatedHttpSource
import exh.source.NhGallery
import exh.source.NhServers
import exh.source.fillFrom
import exh.source.layeredMangaUpdate
import exh.source.nhJson
import exh.source.nhPagePreviews
import exh.source.nhPreferredTitle
import exh.util.SourceTagsUtil
import exh.util.urlImportFetchSearchMangaSuspend
import okhttp3.CacheControl
import okhttp3.Response

class NHentai(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<NHentaiSearchMetadata, Response>,
    UrlImportableSource,
    NamespaceSource,
    PagePreviewSource {
    override val metaClass = NHentaiSearchMetadata::class
    override fun newMetaInstance() = NHentaiSearchMetadata()

    override fun tagSearchQuery(namespace: String, tag: String) = SourceTagsUtil.wrapTagNHentai(namespace, tag)
    override val lang = delegate.lang

    private val sourcePreferences: SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", 0x0000)
    }

    private val servers = NhServers()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = layeredMangaUpdate(manga, chapters, fetchDetails, fetchChapters) { base, response ->
        parseToManga(base, response)
    }

    // resolve a pasted nhentai gallery URL via GalleryAdder; otherwise run a normal search.
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return urlImportFetchSearchMangaSuspend(context, query) {
            super<DelegatedHttpSource>.getSearchManga(page, query, filters)
        }
    }

    override suspend fun parseIntoMetadata(metadata: NHentaiSearchMetadata, input: Response) {
        servers.ensure(client, headers)
        val gallery = nhJson.decodeFromString<NhGallery>(input.body.string())
        metadata.fillFrom(gallery, servers.thumbServer, nhPreferredTitle(sourcePreferences))
    }

    override val matchingHosts = listOf(
        "nhentai.net",
    )

    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        if (uri.pathSegments.firstOrNull()?.lowercase() != "g") {
            return null
        }

        return "$baseUrl/g/${uri.pathSegments[1]}/"
    }

    override suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage {
        servers.ensure(client, headers)
        val metadata = fetchOrLoadMetadata(manga.id()) {
            client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
        }
        return nhPagePreviews(page, metadata.pageImagePreviewUrls, servers.thumbServer)
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
}
