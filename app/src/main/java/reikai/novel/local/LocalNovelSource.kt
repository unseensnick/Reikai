package reikai.novel.local

import android.content.Context
import android.util.LruCache
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.serialization.json.Json
import mihon.core.archive.archiveReader
import mihon.domain.extension.model.ContentWarning
import reikai.data.novel.NovelStatusCode
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.inUserOrder
import reikai.domain.library.ContentType
import reikai.domain.novel.local.LocalNovelLanguageRepository
import reikai.domain.source.CatalogueEnd
import reikai.novel.download.inlinedImageAddress
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import reikai.novel.local.epub.EpubArchive
import reikai.novel.local.epub.EpubBook
import reikai.novel.local.epub.EpubChapterKey
import reikai.novel.local.epub.EpubChapterRef
import reikai.novel.local.epub.EpubChapters
import reikai.novel.local.epub.EpubException
import reikai.novel.local.epub.EpubMetadata
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import reikai.storage.localCover
import reikai.storage.writeLocalCover
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.time.Instant

/**
 * The novels in the `localnovels` storage folder: each folder is a series and each EPUB in it a volume,
 * in [inVolumeOrder], and an EPUB loose in the folder itself is a series of one. A `details.json`
 * (the manga local source's schema) or a `cover.*` picture in a series folder overrides what the books say.
 * Everything reaches the reader through [NovelSource], so no screen above the seam knows a novel is local.
 */
@Inject
@SingleIn(AppScope::class)
class LocalNovelSource(
    private val context: Context,
    private val storageManager: StorageManager,
    private val json: Json,
    private val languages: LocalNovelLanguageRepository,
    private val userVolumes: LocalVolumeRepository,
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

    // A listing reads only each book's package, which is small; the chapter lists cost a parse of every
    // chapter file, so only the novels being opened keep one.
    private val books = LruCache<String, EpubBook>(BOOK_CACHE_SIZE)
    private val chapterLists = LruCache<String, List<EpubChapterRef>>(CHAPTER_CACHE_SIZE)

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
        val readable = readableVolumes(series.volumes.map { file -> file to runCatching { open(file) } }) {
            LocalNovelMissingException(novelPath)
        }
        val volumes = inVolumeOrder(readable, { it.first.name.orEmpty() }) { it.second.book.metadata.seriesIndex }
            .inUserOrder({ it.first.name.orEmpty() }, userVolumes.volumes(ContentType.NOVELS).value[series.path])
        val (firstFile, first) = volumes.first()
        val details = series.details()
        val meta = first.book.metadata
        // Kept beside the novel, since a novel row has nowhere to hold it; details.json wins, as for every field.
        languages.set(series.path, details?.language ?: meta.language)
        val listed =
            localChapters(series.path, volumes.map { (file, opened) -> file.name.orEmpty() to opened.chapters })
        val chapters = listed.map { chapter ->
            val (file, opened) = volumes[chapter.volume]
            ChapterItem(
                name = if (volumes.size >
                    1
                ) {
                    volumeTitle(chapter.volume, opened.book, chapter.ref)
                } else {
                    chapter.ref.title
                },
                path = chapter.path,
                releaseTime = Instant.fromEpochMilliseconds(file.lastModified()).toString(),
                chapterNumber = chapter.number,
            )
        }
        SourceNovel(
            path = series.path,
            name = details?.title ?: series.title(first.book),
            cover = series.coverAddress(firstFile, first.book),
            genres = (details?.genre ?: meta.genres).joinToString(","),
            summary = details?.description ?: meta.description,
            author = details?.author ?: meta.author,
            artist = details?.artist ?: meta.artist,
            status = details?.status?.let(NovelStatusCode::toSourceString) ?: meta.status,
            chapters = chapters,
        )
    }

    override suspend fun parseChapter(chapterPath: String): String = withIOContext {
        val (seriesPath, volumeName, key) = parseChapterPath(chapterPath)
            ?: throw LocalNovelMissingException(chapterPath)
        val file = seriesAt(seriesPath)?.volumes?.firstOrNull { it.name == volumeName }
            ?: throw LocalNovelMissingException(chapterPath)
        val opened = open(file)
        val index = opened.chapters.indexOfFirst { it.key == key }.takeIf { it >= 0 }
            ?: throw LocalNovelMissingException(chapterPath)
        file.archiveReader(context).use { reader ->
            EpubChapters.render(opened.book, EpubArchive(reader::readEntries), opened.chapters, index) { path, bytes ->
                inlinedImageAddress(bytes, opened.book.imageType(path))
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
                if (volumes.isEmpty()) null else Series(name, volumes, files.toList())
            }
            entry.isFile && entry.isEpub() -> Series(name, listOf(entry), emptyList())
            else -> null
        }
    }

    private fun UniFile.version(): String = "$uri|${length()}|${lastModified()}"

    /** What the book in [file] says about itself, parsed once per version of the file. */
    private fun book(file: UniFile): EpubBook {
        val version = file.version()
        books.get(version)?.let { return it }
        return file.archiveReader(context).use { reader -> EpubBook.parse(EpubArchive(reader::readEntries)) }
            .also { books.put(version, it) }
    }

    /** The book in [file] with its chapters, each parsed once per version of the file. */
    private fun open(file: UniFile): OpenedBook {
        val version = file.version()
        val cachedBook = books.get(version)
        val cachedChapters = chapterLists.get(version)
        if (cachedBook != null && cachedChapters != null) return OpenedBook(cachedBook, cachedChapters)
        return file.archiveReader(context).use { reader ->
            val archive = EpubArchive(reader::readEntries)
            val book = cachedBook ?: EpubBook.parse(archive).also { books.put(version, it) }
            OpenedBook(book, EpubChapters.list(book, archive).also { chapterLists.put(version, it) })
        }
    }

    private fun volumeTitle(index: Int, book: EpubBook, ref: EpubChapterRef): String =
        context.stringResource(MR.strings.local_novel_volume_chapter, volumeLabel(index, book.metadata), ref.title)

    private fun Series.toItem(): NovelItem {
        val first = volumes.firstNotNullOfOrNull { file -> runCatching { book(file) }.getOrNull()?.let { file to it } }
        return NovelItem(
            name = details()?.title ?: first?.let { title(it.second) } ?: path.removeSuffix(EPUB_SUFFIX),
            path = path,
            cover = first?.let { (file, book) -> coverAddress(file, book) },
        )
    }

    /** A series of several books reads as the series they name; one book as its own title. */
    private fun Series.title(first: EpubBook): String {
        val meta = first.metadata
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
     * The series' picture: a cover the user put in its folder, else the picture of [book], read from its
     * [file], copied out once into app storage so a library of covers does not open every EPUB.
     */
    private fun Series.coverAddress(file: UniFile, book: EpubBook): String? {
        files.localCover()?.let { return it.uri.toString() }
        return copyCover(file, book, "${coverCopyPrefix(path)}${file.lastModified()}")
    }

    /** The cover of the book [volume] in the series at [seriesPath], for its volume header. */
    suspend fun volumeCover(seriesPath: String, volume: String): String? = withIOContext {
        val file = seriesAt(seriesPath)?.volumes?.firstOrNull { it.name == volume } ?: return@withIOContext null
        val book = runCatching { book(file) }.getOrNull() ?: return@withIOContext null
        // Under the series' prefix, so dropping the series' cover copies drops these too.
        copyCover(file, book, "${coverCopyPrefix(seriesPath)}${Hash.md5(volume)}-${file.lastModified()}")
    }

    /** [book]'s cover copied out of [file] once, as [name], since a cover loader cannot read inside an EPUB. */
    private fun copyCover(file: UniFile, book: EpubBook, name: String): String? {
        val coverPath = book.coverPath ?: return null
        val target = File(File(context.filesDir, COVER_DIR), "$name.${coverPath.substringAfterLast('.', "jpg")}")
        if (!target.exists()) {
            val bytes = file.archiveReader(context).use { it.readEntries(setOf(coverPath))[coverPath] } ?: return null
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        return target.absolutePath
    }

    /**
     * Writes [stream] as the `cover.*` picture of the series at [novelPath], which [coverAddress] reads
     * before any book's own, and returns its address; null for a loose EPUB, which has no folder to hold
     * one. Twin of `LocalCoverManager.update`, pinned by writeLocalCover, which both write through.
     */
    suspend fun writeCover(novelPath: String, stream: InputStream): String? = withIOContext {
        val folder = storageManager.getLocalNovelsDirectory()?.findFile(novelPath)?.takeIf { it.isDirectory }
            ?: return@withIOContext null
        val written = writeLocalCover(context, folder, stream) ?: return@withIOContext null
        // The folder cover now wins, so the book covers copied for this series are never read again.
        dropCoverCopies(novelPath)
        written.uri.toString()
    }

    /** What the name of every book cover copied for the series at [path] starts with, whatever its version. */
    private fun coverCopyPrefix(path: String) = "${Hash.md5(path)}-"

    private fun dropCoverCopies(path: String) {
        val prefix = coverCopyPrefix(path)
        File(context.filesDir, COVER_DIR).listFiles { copy -> copy.name.startsWith(prefix) }?.forEach(File::delete)
    }

    private data class Series(
        val path: String,
        val volumes: List<UniFile>,
        val files: List<UniFile>,
    ) {
        val lastModified: Long get() = volumes.maxOf { it.lastModified() }
    }

    private class OpenedBook(val book: EpubBook, val chapters: List<EpubChapterRef>)

    companion object {
        const val ID = "local"

        private const val BOOK_CACHE_SIZE = 256
        private const val CHAPTER_CACHE_SIZE = 8
        private const val DETAILS_FILE = "details.json"
        private const val COVER_DIR = "local_novel_covers"
        private const val EPUB_SUFFIX = ".epub"
        private const val PATH_SEPARATOR = "::"

        fun isLocal(sourceId: String?): Boolean = sourceId == ID

        private val BY_NAME = Comparator<Series> { a, b -> a.path.compareToCaseInsensitiveNaturalOrder(b.path) }

        private fun UniFile.isEpub(): Boolean = name.orEmpty().endsWith(EPUB_SUFFIX, ignoreCase = true)

        /**
         * The volumes of a series that open, with their files. Only a book that is protected or not an EPUB
         * is left out, since the user can act on that; any other failure, a read error, fails the refresh
         * instead, because a list missing a volume would delete its chapters' read state. With no volume
         * left, the first one's reason is thrown, else [missing].
         */
        internal fun <F, B> readableVolumes(
            opened: List<Pair<F, Result<B>>>,
            missing: () -> Throwable,
        ): List<Pair<F, B>> {
            opened.firstNotNullOfOrNull { (_, result) -> result.exceptionOrNull()?.takeIf { it !is EpubException } }
                ?.let { throw it }
            return opened.mapNotNull { (file, result) -> result.getOrNull()?.let { file to it } }
                .ifEmpty { throw opened.firstNotNullOfOrNull { it.second.exceptionOrNull() } ?: missing() }
        }

        /**
         * The number a volume at [index] in the series is labelled with: its series number, except in a book
         * Reikai exported, whose number is where its run of chapters starts and so would read "Vol. 51".
         */
        internal fun volumeLabel(index: Int, meta: EpubMetadata): String =
            meta.seriesIndex?.takeUnless { meta.isReikaiExport }?.let(::formatChapterNumber) ?: (index + 1).toString()

        /** One chapter of a series as it is listed: its [volume]'s index, address and place in the series. */
        internal data class LocalChapter(val volume: Int, val ref: EpubChapterRef, val path: String, val number: Double)

        /** Every chapter of [series], volume by volume: each volume is its file name and its chapters. */
        internal fun localChapters(
            series: String,
            volumes: List<Pair<String, List<EpubChapterRef>>>,
        ): List<LocalChapter> {
            var number = 0
            return volumes.flatMapIndexed { index, (volume, chapters) ->
                chapters.map { ref ->
                    LocalChapter(index, ref, chapterPath(series, volume, ref.key), (++number).toDouble())
                }
            }
        }

        /**
         * A chapter's address: its series, its book and the chapter's [EpubChapterKey]. Each part is
         * percent-encoded, so a separator in a folder or file name reads back as part of that name.
         */
        internal fun chapterPath(series: String, volume: String, key: EpubChapterKey): String {
            val at = when {
                key.fragment != null -> "f:${key.fragment}"
                key.heading != null -> "h:${key.heading}"
                else -> "t"
            }
            return listOf(series, volume, key.path, at).joinToString(PATH_SEPARATOR) {
                URLEncoder.encode(it, "UTF-8")
            }
        }

        internal fun parseChapterPath(path: String): Triple<String, String, EpubChapterKey>? {
            val parts = path.split(PATH_SEPARATOR)
                .map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: return null }
            if (parts.size != 4) return null
            val (series, volume, file, at) = parts
            val key = when {
                at.startsWith("f:") -> EpubChapterKey(file, fragment = at.removePrefix("f:"))
                at.startsWith(
                    "h:",
                ) -> EpubChapterKey(file, heading = at.removePrefix("h:").toIntOrNull() ?: return null)
                at == "t" -> EpubChapterKey(file)
                else -> return null
            }
            return Triple(series, volume, key)
        }
    }
}

/** A local novel, book or chapter that is no longer in the folder, or no longer where it was in its book. */
class LocalNovelMissingException(path: String) : Exception("Not found in the local novels folder: $path")
