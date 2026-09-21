package reikai.data.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.CacheControl
import okhttp3.Request
import okio.FileSystem
import reikai.novel.network.NovelImageRequests

/** Coil model for a picture in a novel chapter, fetched with its own source's client and headers. */
data class NovelImage(val url: String, val sourceId: String?)

/** Keyed by address alone, so the picture is one cache entry whichever chapter or mode asks for it. */
class NovelImageKeyer : Keyer<NovelImage> {
    override fun key(data: NovelImage, options: Options): String = data.url
}

class NovelImageFetcher(
    private val image: NovelImage,
    private val options: Options,
    private val requests: NovelImageRequests,
    private val diskCache: DiskCache?,
) : Fetcher {

    override suspend fun fetch() = fetchNovelImage(
        image,
        requests,
        diskCache,
        readCache = options.diskCachePolicy.readEnabled,
        writeCache = options.diskCachePolicy.writeEnabled,
    )

    class Factory(private val requests: Lazy<NovelImageRequests>) : Fetcher.Factory<NovelImage> {
        override fun create(data: NovelImage, options: Options, imageLoader: ImageLoader): Fetcher =
            NovelImageFetcher(data, options, requests.value, imageLoader.diskCache)
    }
}

/**
 * A chapter picture, from Coil's disk cache when it holds one, else fetched and stored there. The
 * WebView reader's intercept calls this too, so the two modes share one copy. The mime type is null
 * off the disk cache, which keeps no headers.
 */
suspend fun fetchNovelImage(
    image: NovelImage,
    requests: NovelImageRequests,
    diskCache: DiskCache?,
    readCache: Boolean,
    writeCache: Boolean,
): SourceFetchResult {
    val key = image.url
    if (readCache) diskCache?.openSnapshot(key)?.let { return it.toResult(diskCache, key, null, DataSource.DISK) }

    val client = requests.forSource(image.sourceId)
    // OkHttp's own cache would keep a failed answer that a retry then reads back.
    val request = Request.Builder().url(image.url).headers(client.headers).cacheControl(NO_STORE).build()
    val response = client.client.newCall(request).awaitSuccess()
    val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
    val editor = diskCache?.takeIf { writeCache }?.openEditor(key)
        ?: return SourceFetchResult(
            ImageSource(response.body.source(), FileSystem.SYSTEM),
            mimeType,
            DataSource.NETWORK,
        )
    val snapshot = try {
        response.use { r -> diskCache.fileSystem.write(editor.data) { r.body.source().readAll(this) } }
        editor.commitAndOpenSnapshot()
    } catch (e: Exception) {
        runCatching { editor.abort() }
        throw e
    }
    return checkNotNull(snapshot) { "The stored picture could not be read back" }
        .toResult(diskCache, key, mimeType, DataSource.NETWORK)
}

private fun DiskCache.Snapshot.toResult(cache: DiskCache, key: String, mimeType: String?, dataSource: DataSource) =
    SourceFetchResult(
        ImageSource(file = data, fileSystem = cache.fileSystem, diskCacheKey = key, closeable = this),
        mimeType,
        dataSource,
    )

private val NO_STORE = CacheControl.Builder().noStore().build()
