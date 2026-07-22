package reikai.data.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.network.await
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.buffer
import okio.sink
import reikai.domain.entry.EntryId
import reikai.novel.network.applyNovelDefaults
import reikai.novel.network.deviceWebViewUserAgent
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

/**
 * Coil [Fetcher] for [NovelCover], the novel twin of
 * [eu.kanade.tachiyomi.data.coil.MangaCoverFetcher]. Leaner than the manga one: no custom-cover
 * override and no per-source OkHttp client. It always uses the shared network client (so it inherits
 * the Cloudflare + FlareSolverr interceptors) and attaches the device WebView User-Agent plus the
 * source [NovelCover.site] as a Referer, which is what some LN cover hosts gate full-image delivery on.
 *
 * Library novel covers persist via [CoverCache]; browse covers fall back to coil's [DiskCache].
 */
class NovelCoverFetcher(
    private val url: String?,
    private val site: String?,
    private val isLibraryNovel: Boolean,
    private val options: Options,
    private val coverFileLazy: Lazy<File?>,
    private val customCoverFileLazy: Lazy<File>,
    private val diskCacheKeyLazy: Lazy<String>,
    private val callFactoryLazy: Lazy<Call.Factory>,
    private val imageLoader: ImageLoader,
) : Fetcher {

    private val diskCacheKey: String get() = diskCacheKeyLazy.value

    override suspend fun fetch(): FetchResult {
        // A user-set custom cover takes precedence over the source cover (matches MangaCoverFetcher).
        // Browse passes novelId 0, whose custom-file path never exists, so the stat is a cheap no-op.
        if (customCoverFileLazy.value.exists()) {
            return fileLoader(customCoverFileLazy.value)
        }
        if (url.isNullOrEmpty()) error("No cover specified")
        return httpLoader()
    }

    private fun fileLoader(file: File): FetchResult = SourceFetchResult(
        source = ImageSource(
            file = file.toOkioPath(),
            fileSystem = FileSystem.SYSTEM,
            diskCacheKey = diskCacheKey,
        ),
        mimeType = "image/*",
        dataSource = DataSource.DISK,
    )

    private suspend fun httpLoader(): FetchResult {
        // Persist library covers in the dedicated cover cache; browse covers use coil's disk cache.
        val libraryCoverCacheFile = if (isLibraryNovel) coverFileLazy.value ?: error("No cover specified") else null
        if (libraryCoverCacheFile?.exists() == true && options.diskCachePolicy.readEnabled) {
            return fileLoader(libraryCoverCacheFile)
        }

        var snapshot = readFromDiskCache()
        try {
            if (snapshot != null) {
                val snapshotCoverCache = moveSnapshotToCoverCache(snapshot, libraryCoverCacheFile)
                if (snapshotCoverCache != null) return fileLoader(snapshotCoverCache)
                return SourceFetchResult(
                    source = snapshot.toImageSource(),
                    mimeType = "image/*",
                    dataSource = DataSource.DISK,
                )
            }

            val response = executeNetworkRequest()
            val responseBody = checkNotNull(response.body) { "Null response source" }
            try {
                val responseCoverCache = writeResponseToCoverCache(response, libraryCoverCacheFile)
                if (responseCoverCache != null) return fileLoader(responseCoverCache)

                snapshot = writeToDiskCache(response)
                if (snapshot != null) {
                    return SourceFetchResult(
                        source = snapshot.toImageSource(),
                        mimeType = "image/*",
                        dataSource = DataSource.NETWORK,
                    )
                }

                return SourceFetchResult(
                    source = ImageSource(source = responseBody.source(), fileSystem = FileSystem.SYSTEM),
                    mimeType = "image/*",
                    dataSource = if (response.cacheResponse != null) DataSource.DISK else DataSource.NETWORK,
                )
            } catch (e: Exception) {
                responseBody.close()
                throw e
            }
        } catch (e: Exception) {
            snapshot?.close()
            throw e
        }
    }

    private suspend fun executeNetworkRequest(): Response {
        val response = callFactoryLazy.value.newCall(newRequest()).await()
        if (!response.isSuccessful && response.code != HTTP_NOT_MODIFIED) {
            response.close()
            throw IOException(response.message)
        }
        return response
    }

    private fun newRequest(): Request {
        val request = Request.Builder()
            .url(url!!)
            // Device UA + source site Referer, shared with the host bridge via [applyNovelDefaults].
            .applyNovelDefaults(deviceWebViewUserAgent(options.context), referer = site)

        when {
            options.networkCachePolicy.readEnabled -> request.cacheControl(CACHE_CONTROL_NO_STORE)
            else -> request.cacheControl(CACHE_CONTROL_NO_NETWORK_NO_CACHE)
        }
        return request.build()
    }

    private fun moveSnapshotToCoverCache(snapshot: DiskCache.Snapshot, cacheFile: File?): File? {
        if (cacheFile == null) return null
        return try {
            imageLoader.diskCache?.run {
                fileSystem.source(snapshot.data).use { input -> writeSourceToCoverCache(input, cacheFile) }
                remove(diskCacheKey)
            }
            cacheFile.takeIf { it.exists() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write snapshot data to novel cover cache ${cacheFile.name}" }
            null
        }
    }

    private fun writeResponseToCoverCache(response: Response, cacheFile: File?): File? {
        if (cacheFile == null || !options.diskCachePolicy.writeEnabled) return null
        return try {
            response.peekBody(Long.MAX_VALUE).source().use { input -> writeSourceToCoverCache(input, cacheFile) }
            cacheFile.takeIf { it.exists() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write response data to novel cover cache ${cacheFile.name}" }
            null
        }
    }

    private fun writeSourceToCoverCache(input: Source, cacheFile: File) {
        cacheFile.parentFile?.mkdirs()
        cacheFile.delete()
        try {
            cacheFile.sink().buffer().use { output -> output.writeAll(input) }
        } catch (e: Exception) {
            cacheFile.delete()
            throw e
        }
    }

    private fun readFromDiskCache(): DiskCache.Snapshot? =
        if (options.diskCachePolicy.readEnabled) imageLoader.diskCache?.openSnapshot(diskCacheKey) else null

    private fun writeToDiskCache(response: Response): DiskCache.Snapshot? {
        val diskCache = imageLoader.diskCache
        val editor = diskCache?.openEditor(diskCacheKey) ?: return null
        try {
            diskCache.fileSystem.write(editor.data) { response.body.source().readAll(this) }
            return editor.commitAndOpenSnapshot()
        } catch (e: Exception) {
            try {
                editor.abort()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    private fun DiskCache.Snapshot.toImageSource(): ImageSource = ImageSource(
        file = data,
        fileSystem = FileSystem.SYSTEM,
        diskCacheKey = diskCacheKey,
        closeable = this,
    )

    class Factory(
        private val callFactoryLazy: Lazy<Call.Factory>,
    ) : Fetcher.Factory<NovelCover> {

        private val coverCache: CoverCache by injectLazy()

        override fun create(data: NovelCover, options: Options, imageLoader: ImageLoader): Fetcher =
            NovelCoverFetcher(
                url = data.url,
                site = data.site,
                isLibraryNovel = data.isNovelFavorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.url) },
                // Custom cover cached under the entry's namespaced name (avoids manga-id collision).
                customCoverFileLazy = lazy {
                    coverCache.getCustomCoverFile(EntryId.Novel(data.novelId))
                },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
            )
    }

    companion object {
        private val CACHE_CONTROL_NO_STORE = CacheControl.Builder().noStore().build()
        private val CACHE_CONTROL_NO_NETWORK_NO_CACHE = CacheControl.Builder().noCache().onlyIfCached().build()
        private const val HTTP_NOT_MODIFIED = 304
    }
}
