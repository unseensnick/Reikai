package exh.md

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.md.utils.MdUtil
import okhttp3.Call
import okhttp3.Request
import okio.FileSystem
import java.io.IOException

/**
 * Cover for an MDList tracker-search result. Wraps the raw MangaDex cover URL so Coil routes it to
 * [MangaDexTrackCoverFetcher] instead of the default (browser User-Agent) network fetcher, which the
 * MangaDex cover CDN rejects with a 400.
 */
data class MangaDexTrackCover(val url: String)

/**
 * Loads an MDList tracker-search cover through the enabled MangaDex source's client + extension
 * headers, the same path that makes Browse and details covers load. Lean by design (no cover-cache
 * or disk-cache plumbing): tracker-search covers are ephemeral, so Coil's memory cache is enough.
 */
class MangaDexTrackCoverFetcher(
    private val url: String,
    private val sourceLazy: Lazy<HttpSource?>,
    private val callFactoryLazy: Lazy<Call.Factory>,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val source = sourceLazy.value
        val client = source?.client ?: callFactoryLazy.value
        val request = Request.Builder()
            .url(url)
            .apply { source?.headers?.let { headers(it) } }
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            response.close()
            throw IOException(response.message)
        }
        val body = checkNotNull(response.body) { "Null response source" }
        return SourceFetchResult(
            source = ImageSource(source = body.source(), fileSystem = FileSystem.SYSTEM),
            mimeType = "image/*",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(
        private val callFactoryLazy: Lazy<Call.Factory>,
    ) : Fetcher.Factory<MangaDexTrackCover> {

        override fun create(data: MangaDexTrackCover, options: Options, imageLoader: ImageLoader): Fetcher =
            MangaDexTrackCoverFetcher(
                url = data.url,
                sourceLazy = lazy { MdUtil.getEnabledMangaDex() },
                callFactoryLazy = callFactoryLazy,
            )
    }
}

class MangaDexTrackCoverKeyer : Keyer<MangaDexTrackCover> {
    override fun key(data: MangaDexTrackCover, options: Options): String = data.url
}
