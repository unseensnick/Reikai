package mihon.data.cache

import android.content.Context
import android.text.format.Formatter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.DownloadStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.common.cache.TtlDiskCache
import mihon.core.metro.AppCoroutineScope
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * The page lists and images of chapters read online.
 *
 * Page lists expire soon, as sources sign or rotate their image URLs. Images last longer.
 */
@Inject
@SingleIn(AppScope::class)
class ChapterCache(
    private val context: Context,
    private val json: Json,
    @AppCoroutineScope scope: CoroutineScope,
    // RK --> novel tests run the whole session on virtual time, which neither may leave or move
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    newCache: (File, File, Duration) -> TtlDiskCache = { dir, index, ttl ->
        TtlDiskCache(dir, index, ttl, scope, dispatcher = dispatcher)
    },
    // RK <--
) {

    private val root = File(context.cacheDir, "chapter_cache")

    // Out of the cache directory, so the system clearing that leaves the indexes
    private val indexes = File(context.noBackupFilesDir, "chapter_cache")

    private val pageLists = newCache(File(root, "pages"), File(indexes, "pages.index"), 1.hours) // RK

    private val images = newCache(File(root, "images"), File(indexes, "images.index"), 1.days) // RK

    // RK: a novel chapter's text, kept apart since it holds no signed url to expire
    private val texts = newCache(File(root, "texts"), File(indexes, "texts.index"), 7.days)

    init {
        scope.launch {
            pageLists.warmUp()
            images.warmUp()
            texts.warmUp() // RK
            // RK: the DiskLruCache this replaced, which nothing clears or counts any more
            withContext(dispatcher) { File(context.cacheDir, "chapter_disk_cache").deleteRecursively() }
        }
    }

    suspend fun readableSize(): String = Formatter.formatFileSize(
        context,
        pageLists.totalSize() + images.totalSize() + texts.totalSize(), // RK
    )

    suspend fun getPageList(chapter: Chapter): List<Page>? {
        val input = pageLists.open(chapter.key) ?: return null
        return try {
            // RK: coroutine helper in place of upstream's raw dispatcher
            json.decodeFromString(withIOContext { input.bufferedReader().use { it.readText() } })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Failed to read page list from cache" }
            pageLists.remove(chapter.key)
            null
        }
    }

    suspend fun putPageList(chapter: Chapter, pages: List<Page>) {
        try {
            val value = json.encodeToString(pages).toByteArray()
            pageLists.put(chapter.key) { it.write(value) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Failed to put page list to cache" }
        }
    }

    // RK --> a reload from the source drops what the page loader would otherwise serve again
    suspend fun removePageList(chapter: Chapter) {
        pageLists.remove(chapter.key)
    }
    // RK <--

    suspend fun isImageInCache(imageUrl: String): Boolean = imageUrl in images

    /**
     * The image's file, or null when it isn't cached.
     */
    suspend fun getImageFile(imageUrl: String): File? = images.get(imageUrl)

    /**
     * Opens the image, or returns null when it isn't cached.
     */
    suspend fun openImage(imageUrl: String): InputStream? = images.open(imageUrl)

    suspend fun removeImage(imageUrl: String) {
        images.remove(imageUrl)
    }

    /**
     * Stores the image [response] holds, and closes it.
     *
     * @param live also receives the bytes as they arrive.
     */
    @Throws(IOException::class)
    suspend fun putImage(imageUrl: String, response: Response, live: DownloadStream? = null): File {
        response.body.use { body ->
            live?.reserve(body.contentLength())
            return images.put(imageUrl) { out ->
                body.source().use { source ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        live?.append(buffer, 0, n)
                    }
                }
            }
        }
    }

    // RK --> a novel chapter's text, kept by NovelChapterTextCache so clearing covers both content types
    suspend fun getText(key: String): String? {
        val input = texts.open(key) ?: return null
        return withContext(dispatcher) { input.bufferedReader().use { it.readText() } }
    }

    suspend fun removeText(key: String) {
        texts.remove(key)
    }

    suspend fun putText(key: String, text: String) {
        texts.put(key) { it.write(text.toByteArray()) }
    }
    // RK <--

    /**
     * Removes everything, returning how many entries there were.
     */
    suspend fun clear(): Int = pageLists.clear() + images.clear() + texts.clear() // RK

    private val Chapter.key: String
        get() = "$mangaId$url"
}
