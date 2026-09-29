package reikai.data.coil

import coil3.ImageLoader
import coil3.fetch.Fetcher
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.NovelCover
import reikai.novel.network.NovelImageRequests

/**
 * Coil [Fetcher.Factory] for [NovelCover], fetched and cached by [MangaCoverFetcher] like a manga's. The
 * client and headers are the source's own image ones, the same every novel picture is fetched with
 * ([NovelImageRequests]). No cover colour is taken here: novels pick theirs through `seedColor`.
 */
class NovelCoverFactory(
    private val requests: Lazy<NovelImageRequests>,
    private val coverCache: CoverCache,
    private val mangaCoverMetadata: MangaCoverMetadata,
) : Fetcher.Factory<NovelCover> {

    override fun create(data: NovelCover, options: Options, imageLoader: ImageLoader): Fetcher =
        MangaCoverFetcher(
            url = data.url,
            isLibraryManga = data.isNovelFavorite,
            mangaCover = null,
            options = options,
            coverFileLazy = lazy { coverCache.getCoverFile(data.url) },
            // Browse passes novelId 0, whose custom-cover file never exists.
            customCoverFileLazy = lazy { coverCache.getCustomCoverFile(EntryId.Novel(data.novelId)) },
            diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
            getClient = {
                val client = requests.value.forSource(data.sourceId)
                CoverRequestClient(client.client, client.headers)
            },
            imageLoader = imageLoader,
            mangaCoverMetadata = mangaCoverMetadata,
        )
}
