package exh.md

import coil3.ImageLoader
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import exh.md.utils.MdUtil
import okhttp3.Call
import reikai.data.coil.coverRequestClient
import reikai.domain.entry.EntryId
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.domain.source.service.SourceManager

/**
 * Cover for an MDList tracker-search result. Wrapped so Coil fetches it with the enabled MangaDex
 * source's client and headers, since the cover CDN 400s the app's browser User-Agent.
 */
data class MangaDexTrackCover(val url: String)

/** Fetches and caches a [MangaDexTrackCover] through [MangaCoverFetcher], like a browse cover. */
class MangaDexTrackCoverFactory(
    private val callFactoryLazy: Lazy<Call.Factory>,
    private val coverCache: CoverCache,
    private val mangaCoverMetadata: MangaCoverMetadata,
    private val sourcePreferences: SourcePreferences,
    private val reikaiSourcePreferences: ReikaiSourcePreferences,
    private val sourceManager: SourceManager,
) : Fetcher.Factory<MangaDexTrackCover> {

    override fun create(data: MangaDexTrackCover, options: Options, imageLoader: ImageLoader): Fetcher =
        MangaCoverFetcher(
            url = data.url,
            isLibraryManga = false,
            mangaCover = null,
            options = options,
            coverFileLazy = lazy { null },
            // A search result has no stored row, and row ids start at 1, so no custom cover is found.
            customCoverFileLazy = lazy { coverCache.getCustomCoverFile(EntryId.Manga(0)) },
            diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
            getClient = {
                MdUtil.getEnabledMangaDex(sourcePreferences, reikaiSourcePreferences, sourceManager)
                    .coverRequestClient(callFactoryLazy)
            },
            imageLoader = imageLoader,
            mangaCoverMetadata = mangaCoverMetadata,
        )
}

class MangaDexTrackCoverKeyer : Keyer<MangaDexTrackCover> {
    override fun key(data: MangaDexTrackCover, options: Options): String = data.url
}
