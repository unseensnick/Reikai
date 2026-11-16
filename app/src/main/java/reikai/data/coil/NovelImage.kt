package reikai.data.coil

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.imageLoader
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.awaitSuccess
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import org.jsoup.Jsoup
import reikai.novel.content.NovelChapterAddress
import reikai.novel.content.NovelImageSources
import reikai.novel.local.LocalNovelSource
import reikai.novel.network.NovelImageRequests
import java.io.FileNotFoundException

/** Coil model for a picture in a novel chapter, fetched with its own source's client and headers. */
data class NovelImage(val url: String, val sourceId: String?)

/**
 * The key a chapter picture is cached under, in memory and on disk: its address alone, so the picture is one
 * cache entry whichever chapter or mode asks for it. The text renderer reads the stored entry by it too. A
 * local book's picture is keyed by its source as well, since a memory hit skips the fetch's local-source check.
 */
fun novelImageCacheKey(url: String, sourceId: String?): String =
    if (LocalNovelSource.isPictureAddress(url)) "$url#$sourceId" else url

class NovelImageKeyer : Keyer<NovelImage> {
    override fun key(data: NovelImage, options: Options): String = novelImageCacheKey(data.url, data.sourceId)
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
    val key = novelImageCacheKey(image.url, image.sourceId)
    val isLocalPicture = LocalNovelSource.isPictureAddress(image.url)
    // Another source's chapter naming a book's picture gets nothing, cached or not.
    if (isLocalPicture && !LocalNovelSource.isLocal(image.sourceId)) throw noLocalPicture()
    if (readCache) diskCache?.openSnapshot(key)?.let { return it.toResult(diskCache, key, null, DataSource.DISK) }
    val cache = diskCache?.takeIf { writeCache }

    // Stored in the cache like a fetched one, since the text renderer reads its size and slices from that file.
    if (isLocalPicture) {
        val picture = requests.localPicture(image.url, image.sourceId) ?: throw noLocalPicture()
        return stored(cache, key, picture.mime, DataSource.DISK, Buffer().write(picture.bytes))
    }
    val response = requests.forSource(image.sourceId).newCall(image.url).awaitSuccess()
    val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
    return stored(cache, key, mimeType, DataSource.NETWORK, response.body.source())
}

/**
 * An address a chapter picture is loaded from as a [NovelImage]: on the web, or in a local novel's book.
 * Both renderers and the picture viewer ask this, so a kind of address one loads the others load too.
 */
fun isNovelImageAddress(address: String): Boolean =
    NovelChapterAddress.isWebAddress(address) || LocalNovelSource.isPictureAddress(address)

/**
 * Drops every picture [html] names (img src and srcset, resolved against [baseUrl]) from coil's caches, so a
 * reload from the source fetches them again, as manga's drops its page images. Both renderers read these caches.
 */
fun evictChapterPictures(
    html: String,
    baseUrl: String?,
    sourceId: String?,
    memoryCache: MemoryCache?,
    diskCache: DiskCache?,
) {
    val document = Jsoup.parseBodyFragment(html)
    val addresses = document.select("img[src]").map { it.attr("src") } +
        document.select("[srcset]").flatMap { element ->
            NovelImageSources.parseSrcset(element.attr("srcset")).map { it.first }
        }
    val keys = addresses.map { NovelChapterAddress.absolute(baseUrl, it) }
        .filter(::isNovelImageAddress)
        .mapTo(HashSet()) { novelImageCacheKey(it, sourceId) }
    keys.forEach { diskCache?.remove(it) }
    // A memory entry is keyed by the picture and the size it was decoded at, so each size goes.
    memoryCache?.keys?.filter { it.key in keys }?.forEach(memoryCache::remove)
}

private fun noLocalPicture() = FileNotFoundException("No such picture in the local novels folder")

/** [source] written into [cache] under [key] and read back from there, or handed on as it is with no cache. */
private fun stored(
    cache: DiskCache?,
    key: String,
    mimeType: String?,
    dataSource: DataSource,
    source: BufferedSource,
): SourceFetchResult {
    val editor = cache?.openEditor(key)
        ?: return SourceFetchResult(ImageSource(source, FileSystem.SYSTEM), mimeType, dataSource)
    val snapshot = try {
        source.use { s -> cache.fileSystem.write(editor.data) { s.readAll(this) } }
        editor.commitAndOpenSnapshot()
    } catch (e: Exception) {
        runCatching { editor.abort() }
        throw e
    }
    return checkNotNull(snapshot) { "The stored picture could not be read back" }
        .toResult(cache, key, mimeType, dataSource)
}

private fun DiskCache.Snapshot.toResult(cache: DiskCache, key: String, mimeType: String?, dataSource: DataSource) =
    SourceFetchResult(
        ImageSource(file = data, fileSystem = cache.fileSystem, diskCacheKey = key, closeable = this),
        mimeType,
        dataSource,
    )

/** [evictChapterPictures] over the app's image loader, what a novel chapter's Reload from source runs. */
@Inject
class NovelChapterPictureCache(private val context: Context) {
    fun evict(html: String, baseUrl: String?, sourceId: String?) {
        val loader = context.imageLoader
        evictChapterPictures(html, baseUrl, sourceId, loader.memoryCache, loader.diskCache)
    }
}
