package reikai.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import mihon.core.archive.ArchiveReader
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.novel.insertOpenedNovel
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.novel.host.NovelItem
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubArchive
import reikai.novel.local.epub.EpubBook
import reikai.novel.local.epub.EpubException
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import java.io.IOException

/** One file the user picked, as the system names it. */
data class ImportFile(val uri: Uri, val name: String, val size: Long)

/**
 * One series an import will create or add to: [files] in the order they become volumes or chapters, the
 * [type] it imports as, and [cover] for the preview. [canBeEitherType] marks an EPUB, which can be a novel
 * or, laid out as fixed pages, a comic.
 */
data class ImportGroup(
    val key: String,
    val type: ContentType,
    val title: String,
    val author: String?,
    val cover: ByteArray?,
    val files: List<ImportFile>,
    val canBeEitherType: Boolean,
)

/** A file or series an import had to skip, and why. */
data class ImportProblem(val name: String, val reason: Reason) {
    enum class Reason { Unreadable, Protected, Unsupported, NoStorage, CopyFailed, AddFailed }
}

/** What an import did: the series it touched, by [ImportedSeries.type], and what it had to skip. */
data class ImportResult(val series: List<ImportedSeries>, val problems: List<ImportProblem>)

data class ImportedSeries(val type: ContentType, val title: String, val path: String, val entryId: Long?)

/**
 * Copies books and archives the user picked into the local source folders, `localnovels` for novels and
 * `local` for manga, a folder per series, and adds them to the library through each type's own batch add.
 * The files are copied, not linked, since a picked document's permission does not outlive the app.
 */
@Inject
class LocalImport(
    private val context: Context,
    private val storageManager: StorageManager,
    private val localNovels: LocalNovelSource,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val libraryPreferences: LibraryPreferences,
    private val chapterNumberOverrides: ChapterNumberOverrideRepository,
    private val novelAdder: NovelLibraryAdder,
    private val mangaAdder: MangaLibraryAdder,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) {

    /** Reads each of [uris] and groups them into series; a file that cannot be read is a problem, not a group. */
    suspend fun preview(uris: List<Uri>): Pair<List<ImportGroup>, List<ImportProblem>> = withIOContext {
        val problems = mutableListOf<ImportProblem>()
        val read = uris.distinct().mapNotNull { uri ->
            val file = describe(uri)
            runCatching { readFile(file) }
                .onFailure { problems += ImportProblem(file.name, readFailure(it)) }
                .getOrNull()
        }
        val groups = read.groupBy { it.type to groupKey(it.series) }.map { (key, members) ->
            val ordered = members.sortedWith(
                compareBy<ReadFile> { it.seriesIndex ?: Double.MAX_VALUE }
                    .thenComparator { a, b -> a.file.name.compareToCaseInsensitiveNaturalOrder(b.file.name) },
            )
            val first = ordered.first()
            ImportGroup(
                key = "${key.first}:${key.second}",
                type = key.first,
                title = if (ordered.size > 1) first.series else first.title ?: first.series,
                author = first.author,
                cover = first.cover,
                files = ordered.map { it.file },
                canBeEitherType = ordered.all { it.isEpub },
            )
        }
        groups to problems
    }

    /**
     * Copies each group into its folder and, with [addToLibrary], adds it under [categoryIds]. A series
     * folder that exists already takes the new files beside its own, so a later volume joins its series.
     */
    suspend fun import(groups: List<ImportGroup>, addToLibrary: Boolean, categoryIds: List<Long>): ImportResult =
        withIOContext {
            val problems = mutableListOf<ImportProblem>()
            val series = groups.mapNotNull { group ->
                val root = when (group.type) {
                    ContentType.NOVELS -> storageManager.getLocalNovelsDirectory()
                    else -> storageManager.getLocalSourceDirectory()
                }
                if (root == null) {
                    problems += ImportProblem(group.title, ImportProblem.Reason.NoStorage)
                    return@mapNotNull null
                }
                val folderName = DiskUtil.buildValidFilename(group.title)
                val folder = root.findFile(folderName)?.takeIf { it.isDirectory } ?: root.createDirectory(folderName)
                if (folder == null) {
                    problems += ImportProblem(group.title, ImportProblem.Reason.NoStorage)
                    return@mapNotNull null
                }
                val numbered = group.files.size > 1 && folder.listFiles().isNullOrEmpty()
                var copied = 0
                group.files.forEachIndexed { index, file ->
                    // Numbered when a new series gets several files, so the folder reads in the order shown.
                    val name = if (numbered) "%02d - %s".format(index + 1, file.name) else file.name
                    runCatching { copy(file, folder, name) }
                        .onSuccess { if (it) copied++ }
                        .onFailure { problems += ImportProblem(file.name, ImportProblem.Reason.CopyFailed) }
                }
                if (copied == 0 && folder.listFiles().isNullOrEmpty()) {
                    folder.delete()
                    return@mapNotNull null
                }
                val entryId = if (addToLibrary) {
                    runCatching { add(group.type, folderName, categoryIds) }
                        .onFailure { problems += ImportProblem(group.title, ImportProblem.Reason.AddFailed) }
                        .getOrNull()
                } else {
                    null
                }
                ImportedSeries(group.type, group.title, folderName, entryId)
            }
            ImportResult(series, problems)
        }

    /** Stores the series the folder [path] now holds, fills in its chapters and favorites it. */
    private suspend fun add(type: ContentType, path: String, categoryIds: List<Long>): Long? = when (type) {
        ContentType.NOVELS -> {
            val parsed = localNovels.parseNovel(path)
            val novel = insertOpenedNovel(
                parsed,
                LocalNovelSource.ID,
                novelRepository,
                novelChapterRepository,
                libraryPreferences,
                chapterNumberOverrides,
            )
            novelAdder.addToBatch(NovelItem(parsed.name ?: path, path, parsed.cover), LocalNovelSource.ID, categoryIds)
            novel?.id ?: novelRepository.getByUrlAndSource(path, LocalNovelSource.ID)?.id
        }
        else -> {
            val stored =
                networkToLocalManga(
                    SManga.create().apply {
                        url = path
                        title = path
                    }.toDomainManga(LocalSource.ID),
                )
            updateMangaFromRemote(stored, fetchDetails = true, fetchChapters = true, manualFetch = true).getOrThrow()
            mangaAdder.addToBatch(stored.id, categoryIds)
            stored.id
        }
    }

    /** Copies [file] into [folder] as [name]; false when a file of that name and size is already there. */
    private fun copy(file: ImportFile, folder: UniFile, name: String): Boolean {
        val existing = folder.findFile(name) ?: folder.listFiles().orEmpty().firstOrNull { it.name == file.name }
        if (existing != null && existing.length() == file.size) return false
        val target = folder.createFile(uniqueName(folder, name)) ?: throw IOException("Could not create $name")
        try {
            val input =
                context.contentResolver.openInputStream(file.uri) ?: throw IOException("Could not open ${file.name}")
            input.use { source -> target.openOutputStream().use { source.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return true
    }

    private fun uniqueName(folder: UniFile, name: String): String {
        if (folder.findFile(name) == null) return name
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        return generateSequence(2) { it + 1 }.map { "$stem ($it).$extension" }.first { folder.findFile(it) == null }
    }

    private fun describe(uri: Uri): ImportFile {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0)
                        size = cursor.getLong(1)
                    }
                }
        }
        return ImportFile(uri, name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file", size)
    }

    private fun readFile(file: ImportFile): ReadFile {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val pfd =
            context.contentResolver.openFileDescriptor(file.uri, "r")
                ?: throw IOException("Could not open ${file.name}")
        val stem = file.name.substringBeforeLast('.')
        return pfd.use { ArchiveReader(it) }.use { reader ->
            val kind = kindOf(file, extension, reader)
            // Named for its kind when its name did not say, so the local source lists the copy.
            val named = when {
                kind == "epub" && extension != "epub" -> file.copy(name = "${file.name}.epub")
                kind == "comic" && extension !in MANGA_ARCHIVES -> file.copy(name = "${file.name}.cbz")
                else -> file
            }
            when (kind) {
                "epub" -> {
                    val archive = EpubArchive(reader::readEntries)
                    val book = EpubBook.parse(archive)
                    val meta = book.metadata
                    ReadFile(
                        file = named,
                        type = if (book.isFixedLayout) ContentType.MANGA else ContentType.NOVELS,
                        title = meta.title,
                        series = meta.series ?: meta.title ?: stem,
                        seriesIndex = meta.seriesIndex,
                        author = meta.author,
                        cover = book.coverPath?.let { archive.read(setOf(it))[it] },
                        isEpub = true,
                    )
                }
                "comic" -> {
                    val comicInfo = reader.readEntries(setOf(COMIC_INFO))[COMIC_INFO]?.toString(Charsets.UTF_8)
                    // The first page in reading order stands in for a cover, as the manga local source's does.
                    val firstPage = reader.useEntries { entries ->
                        entries.filter { it.isFile && it.name.substringAfterLast('.').lowercase() in PAGE_EXTENSIONS }
                            .map { it.name }
                            .toList()
                    }.minWithOrNull { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                    val series = comicInfo?.let { COMIC_SERIES.find(it)?.groupValues?.get(1)?.trim() }?.ifEmpty { null }
                    ReadFile(
                        file = named,
                        type = ContentType.MANGA,
                        title = null,
                        series = series ?: stem.replace(TRAILING_NUMBER, "").trim().ifEmpty { stem },
                        seriesIndex = null,
                        author = comicInfo?.let { COMIC_WRITER.find(it)?.groupValues?.get(1)?.trim() },
                        cover = firstPage?.let { reader.readEntries(setOf(it))[it] },
                        isEpub = false,
                    )
                }
                else -> throw UnsupportedFileException()
            }
        }
    }

    /**
     * Which kind of file this is: by its name, else by the type its provider reports, else by what the archive
     * holds, since a file shared from a cloud drive or a chat can arrive with no extension at all.
     */
    private fun kindOf(file: ImportFile, extension: String, reader: ArchiveReader): String? {
        if (extension == "epub") return "epub"
        if (extension in MANGA_ARCHIVES) return "comic"
        when (context.contentResolver.getType(file.uri)) {
            "application/epub+zip" -> return "epub"
            in COMIC_TYPES -> return "comic"
        }
        val names = runCatching { reader.useEntries { entries -> entries.map { it.name }.toList() } }.getOrNull()
            ?: return null
        return when {
            "META-INF/container.xml" in names -> "epub"
            names.any { it.substringAfterLast('.').lowercase() in PAGE_EXTENSIONS } -> "comic"
            else -> null
        }
    }

    private fun readFailure(error: Throwable): ImportProblem.Reason = when {
        error is UnsupportedFileException -> ImportProblem.Reason.Unsupported
        error is EpubException && error.kind == EpubException.Kind.Protected -> ImportProblem.Reason.Protected
        else -> ImportProblem.Reason.Unreadable
    }

    private class UnsupportedFileException : IOException()

    private class ReadFile(
        val file: ImportFile,
        val type: ContentType,
        val title: String?,
        val series: String,
        val seriesIndex: Double?,
        val author: String?,
        val cover: ByteArray?,
        val isEpub: Boolean,
    )

    companion object {
        /** The file types an import picker offers, the formats the two local sources read. */
        val MIME_TYPES = arrayOf(
            "application/epub+zip",
            "application/zip",
            "application/x-cbz",
            "application/vnd.comicbook+zip",
            "application/x-cbr",
            "application/vnd.comicbook-rar",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/x-cb7",
            "application/x-tar",
            "application/octet-stream",
        )

        private val MANGA_ARCHIVES = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt")
        private val COMIC_TYPES = setOf(
            "application/x-cbz",
            "application/vnd.comicbook+zip",
            "application/x-cbr",
            "application/vnd.comicbook-rar",
            "application/x-cb7",
        )
        private const val COMIC_INFO = "ComicInfo.xml"
        private val PAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl")
        private val COMIC_SERIES = Regex("<Series>([^<]*)</Series>")
        private val COMIC_WRITER = Regex("<Writer>([^<]*)</Writer>")
        private val TRAILING_NUMBER =
            Regex("""[\s_\-.]*(?:vol(?:ume)?|v|ch(?:apter)?|c|#)?\.?\s*\d+(?:\.\d+)?\s*$""", RegexOption.IGNORE_CASE)

        /** Series that differ only in case, spacing or punctuation are one series. */
        internal fun groupKey(series: String): String = series.lowercase().filter { it.isLetterOrDigit() }
    }
}
