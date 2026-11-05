package reikai.novel.local

import android.content.Context
import android.util.LruCache
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.serialization.json.Json
import mihon.core.archive.archiveReader
import mihon.domain.extension.model.ContentWarning
import reikai.data.novel.NovelStatusCode
import reikai.domain.source.CatalogueEnd
import reikai.novel.download.inlinedImageAddress
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import reikai.novel.local.epub.EpubArchive
import reikai.novel.local.epub.EpubBook
import reikai.novel.local.epub.EpubChapterRef
import reikai.novel.local.epub.EpubChapters
import reikai.novel.local.epub.EpubCut
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import java.io.File
import kotlin.time.Instant

/**
 * The novels in the `localnovels` storage folder: each folder is a series and each EPUB in it a volume,
 * in natural name order, and an EPUB loose in the folder itself is a series of one. A `details.json`
 * (the manga local source's schema) or a `cover.*` picture in a series folder overrides what the books say.
 * Everything reaches the reader through [NovelSource], so no screen above the seam knows a novel is local.
 */
@Inject
@SingleIn(AppScope::class)
class LocalNovelSource(
    private val context: Context,
    private val storageManager: StorageManager,
    private val json: Json,
) : NovelSource {

    override val id: String = ID
    override val name: String get() = context.stringResource(MR.strings.local_source)
    override val version: String = "1"
    override val site: String = ""
    override val lang: String = "other"

    // The manga local source's own icon, so the two read as one kind of source in Browse.
    override val iconUrl: String get() = "android.resource://${context.packageName}/${R.mipmap.ic_local_source}"
    override val format: NovelExtensionFormat? = null
    override val extensionName: String get() = name
    override val contentWarning: ContentWarning = ContentWarning.SAFE
    override val supportsLatest: Boolean = true

    private val books = LruCache<String, OpenedBook>(BOOK_CACHE_SIZE)

    override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
        withIOContext {
            val series = allSeries()
            val ordered = when (listing) {
                NovelListing.Popular -> series.sortedWith(BY_NAME)
                NovelListing.Latest -> series.sortedByDescending { it.lastModified }
            }
            NovelItemsPage(ordered.map { it.toItem() }, CatalogueEnd.Reported(false))
        }

    override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage = withIOContext {
        val items = allSeries()
            .sortedWith(BY_NAME)
            .map { it.toItem() }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        NovelItemsPage(items, CatalogueEnd.Reported(false))
    }

    override suspend fun parseNovel(novelPath: String): SourceNovel = withIOContext {
        val series = seriesAt(novelPath) ?: throw LocalNovelMissingException(novelPath)
        // A book that cannot be read is left out of its series; a series none of whose books can be read
        // fails with the first one's reason (protected, not an EPUB), which is what the user can act on.
        val opened = series.volumes.map { file -> file to runCatching { open(file) } }
        val volumes = opened.mapNotNull { (file, result) -> result.getOrNull()?.let { file to it } }
        val first = volumes.firstOrNull()?.second
            ?: throw opened.firstNotNullOfOrNull { it.second.exceptionOrNull() }
                ?: LocalNovelMissingException(novelPath)
        val details = series.details()
        val meta = first.book.metadata
        var number = 0
        val chapters = volumes.flatMapIndexed { volumeIndex, (file, opened) ->
            opened.chapters.map { ref ->
                number++
                ChapterItem(
                    name = if (volumes.size > 1) volumeTitle(volumeIndex, opened, ref) else ref.title,
                    path = chapterPath(series.path, file.name.orEmpty(), ref.start),
                    releaseTime = Instant.fromEpochMilliseconds(file.lastModified()).toString(),
                    chapterNumber = number.toDouble(),
                )
            }
        }
        SourceNovel(
            path = series.path,
            name = details?.title ?: series.title(first),
            cover = series.coverAddress(first),
            genres = (details?.genre ?: meta.genres).joinToString(","),
            summary = details?.description ?: meta.description,
            author = details?.author ?: meta.author,
            artist = details?.artist ?: meta.artist,
            status = details?.status?.let(NovelStatusCode::toSourceString) ?: meta.status,
            chapters = chapters,
        )
    }

    override suspend fun parseChapter(chapterPath: String): String = withIOContext {
        val (seriesPath, volumeName, start) = parseChapterPath(chapterPath)
            ?: throw LocalNovelMissingException(chapterPath)
        val file = seriesAt(seriesPath)?.volumes?.firstOrNull { it.name == volumeName }
            ?: throw LocalNovelMissingException(chapterPath)
        val opened = open(file)
        val index = opened.chapters.indexOfFirst { it.start == start }.takeIf { it >= 0 }
            ?: throw LocalNovelMissingException(chapterPath)
        file.archiveReader(context).use { reader ->
            EpubChapters.render(opened.book, EpubArchive(reader::readEntries), opened.chapters, index) { path, bytes ->
                inlinedImageAddress(bytes, opened.book.mediaTypes[path]?.takeIf { it.startsWith("image/") })
            }
        }
    }

    // A local novel has no page on the web: a blank address hides WebView, Share and Copy link.
    override suspend fun webUrl(path: String, isNovel: Boolean): String = ""

    /** Every series in the folder: its folders holding an EPUB, then its loose EPUBs. */
    private fun allSeries(): List<Series> {
        val root = storageManager.getLocalNovelsDirectory() ?: return emptyList()
        return root.listFiles().orEmpty().mapNotNull { entry -> seriesOf(entry) }
    }

    private fun seriesAt(path: String): Series? =
        storageManager.getLocalNovelsDirectory()?.findFile(path)?.let(::seriesOf)

    private fun seriesOf(entry: UniFile): Series? {
        val name = entry.name ?: return null
        if (name.startsWith('.')) return null
        return when {
            entry.isDirectory -> {
                val files = entry.listFiles().orEmpty()
                val volumes = files.filter { it.isFile && it.isEpub() }
                    .sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
                if (volumes.isEmpty()) null else Series(name, volumes, entry, files.toList())
            }
            entry.isFile && entry.isEpub() -> Series(name, listOf(entry), null, emptyList())
            else -> null
        }
    }

    /** The book in [file], parsed once per version of the file. */
    private fun open(file: UniFile): OpenedBook {
        val key = "${file.uri}|${file.length()}|${file.lastModified()}"
        books.get(key)?.let { return it }
        return file.archiveReader(context).use { reader ->
            val archive = EpubArchive(reader::readEntries)
            val book = EpubBook.parse(archive)
            OpenedBook(book, EpubChapters.list(book, archive))
        }.also { books.put(key, it) }
    }

    private fun volumeTitle(index: Int, opened: OpenedBook, ref: EpubChapterRef): String {
        val number = opened.book.metadata.seriesIndex?.let(::formatNumber) ?: (index + 1).toString()
        return context.stringResource(MR.strings.local_novel_volume_chapter, number, ref.title)
    }

    private fun Series.toItem(): NovelItem {
        val opened = volumes.firstNotNullOfOrNull { runCatching { open(it) }.getOrNull() }
        return NovelItem(
            name = details()?.title ?: opened?.let { title(it) } ?: path.removeSuffix(EPUB_SUFFIX),
            path = path,
            cover = opened?.let { coverAddress(it) },
        )
    }

    /** A series of several books reads as the series they name; one book as its own title. */
    private fun Series.title(first: OpenedBook): String {
        val meta = first.book.metadata
        val fromBooks = if (volumes.size > 1) meta.series ?: meta.title else meta.title ?: meta.series
        return fromBooks ?: path.removeSuffix(EPUB_SUFFIX)
    }

    private fun Series.details(): MangaDetails? = files
        .firstOrNull { it.name.equals(DETAILS_FILE, ignoreCase = true) }
        ?.let { file ->
            runCatching {
                file.openInputStream().use { json.decodeFromString<MangaDetails>(it.reader().readText()) }
            }.getOrNull()
        }

    /**
     * The series' picture: a `cover.*` the user put in its folder, else the first book's own, copied out of
     * the book once into app storage so a library of covers does not open every EPUB.
     */
    private fun Series.coverAddress(first: OpenedBook): String? {
        files.firstOrNull { it.isFile && it.name.orEmpty().substringBeforeLast('.').equals("cover", ignoreCase = true) }
            ?.let { return it.uri.toString() }
        val coverPath = first.book.coverPath ?: return null
        val volume = volumes.first()
        val name = (path + volume.lastModified()).hashCode().toUInt()
        val target = File(File(context.filesDir, COVER_DIR), "$name.${coverPath.substringAfterLast('.', "jpg")}")
        if (!target.exists()) {
            val bytes = volume.archiveReader(context).use { it.readEntries(setOf(coverPath))[coverPath] } ?: return null
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        return target.absolutePath
    }

    private data class Series(
        val path: String,
        val volumes: List<UniFile>,
        val dir: UniFile?,
        val files: List<UniFile>,
    ) {
        val lastModified: Long get() = volumes.maxOf { it.lastModified() }
    }

    private class OpenedBook(val book: EpubBook, val chapters: List<EpubChapterRef>)

    companion object {
        const val ID = "local"

        private const val BOOK_CACHE_SIZE = 8
        private const val DETAILS_FILE = "details.json"
        private const val COVER_DIR = "local_novel_covers"
        private const val EPUB_SUFFIX = ".epub"
        private const val PATH_SEPARATOR = "::"

        fun isLocal(sourceId: String?): Boolean = sourceId == ID

        private val BY_NAME = Comparator<Series> { a, b -> a.path.compareToCaseInsensitiveNaturalOrder(b.path) }

        private fun UniFile.isEpub(): Boolean = name.orEmpty().endsWith(EPUB_SUFFIX, ignoreCase = true)

        private fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        /** A chapter's address: its series, its book and where in the book it starts. */
        internal fun chapterPath(series: String, volume: String, start: EpubCut): String {
            val at = when {
                start.fragment != null -> "f:${start.fragment}"
                start.heading != null -> "h:${start.heading}"
                else -> "t"
            }
            return listOf(series, volume, start.spineIndex.toString(), at).joinToString(PATH_SEPARATOR)
        }

        internal fun parseChapterPath(path: String): Triple<String, String, EpubCut>? {
            val parts = path.split(PATH_SEPARATOR, limit = 4)
            if (parts.size != 4) return null
            val spineIndex = parts[2].toIntOrNull() ?: return null
            val at = parts[3]
            val cut = when {
                at.startsWith("f:") -> EpubCut(spineIndex, fragment = at.removePrefix("f:"))
                at.startsWith("h:") -> EpubCut(spineIndex, heading = at.removePrefix("h:").toIntOrNull() ?: return null)
                at == "t" -> EpubCut(spineIndex)
                else -> return null
            }
            return Triple(parts[0], parts[1], cut)
        }
    }
}

/** A local novel, book or chapter that is no longer in the folder, or no longer where it was in its book. */
class LocalNovelMissingException(path: String) : Exception("Not found in the local novels folder: $path")
