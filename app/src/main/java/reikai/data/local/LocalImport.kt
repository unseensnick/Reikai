package reikai.data.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.presentation.util.formatChapterNumber
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
import reikai.novel.export.epubFileName
import reikai.novel.host.NovelItem
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubArchive
import reikai.novel.local.epub.EpubBook
import reikai.novel.local.epub.EpubException
import reikai.novel.local.inVolumeOrder
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Archive
import java.io.File
import java.io.IOException

/**
 * One file the user picked, as the system names it; [size] is -1 when the provider does not say. [seriesIndex]
 * is the volume number a book states, which names its copy.
 */
data class ImportFile(val uri: Uri, val name: String, val size: Long, val seriesIndex: Double? = null)

/** What an import does with a file the series it joins already holds. */
enum class DuplicateChoice { Replace, KeepBoth, Skip }

/**
 * One series an import will create or add to: [files] in the order they become volumes or chapters, the
 * [type] it imports as, and [cover] for the preview. [canBeEitherType] marks an EPUB, which can be a novel
 * or, laid out as fixed pages, a comic. [existing] is the folder of a series already there that it joins,
 * unless the user made it [separate]; [duplicates] are its files that folder already holds, which
 * [onDuplicate] decides about. Nothing joins or replaces without the preview showing it.
 */
data class ImportGroup(
    val key: String,
    val type: ContentType,
    val title: String,
    val author: String?,
    val cover: ByteArray?,
    val files: List<ImportFile>,
    val canBeEitherType: Boolean,
    val existing: String? = null,
    val separate: Boolean = false,
    val duplicates: Set<Uri> = emptySet(),
    val onDuplicate: DuplicateChoice = DuplicateChoice.KeepBoth,
) {
    /** The folder of the series this group goes into, when it joins one already there. */
    val joins: String? get() = existing?.takeUnless { separate }
}

/** A file or series an import had to skip, and why; [uri] is the file's, null for a whole series. */
data class ImportProblem(val name: String, val reason: Reason, val uri: Uri? = null) {
    enum class Reason { Unreadable, Protected, Unsupported, NoStorage, CopyFailed, AddFailed }
}

/** What an import did: the series it touched, by [ImportedSeries.type], and what it had to skip. */
data class ImportResult(val series: List<ImportedSeries>, val problems: List<ImportProblem>)

/** A series an import touched. [key] is its group's, unique where two renamed groups can share a [path]. */
data class ImportedSeries(
    val key: String,
    val type: ContentType,
    val title: String,
    val path: String,
    val entryId: Long?,
)

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
            val file = context.contentResolver.describe(uri)
            runCatching { readFile(file) }
                .onFailure { problems += ImportProblem(file.name, readFailure(it), uri) }
                .getOrNull()
        }
        groupSeries(read).map { inspect(it) } to problems
    }

    /**
     * [group] with the series already there that it joins: [target]'s folder when the user picked one, else
     * the folder its title matches the way a batch groups its files. Its files that folder already holds, by
     * size, are its duplicates.
     */
    suspend fun inspect(group: ImportGroup, target: String? = null): ImportGroup = withIOContext {
        val root = rootOf(group.type) ?: return@withIOContext group
        val folder = target?.let { root.findFile(it) }
            ?: root.listFiles().orEmpty().firstOrNull {
                it.isDirectory &&
                    groupKey(it.name.orEmpty()) == groupKey(group.title)
            }
        val sizes = folder?.listFiles().orEmpty().filter { it.isFile }.map { it.length() }.toSet()
        group.copy(
            existing = folder?.name,
            duplicates = group.files.filter { it.size > 0 && it.size in sizes }.map { it.uri }.toSet(),
        )
    }

    private fun rootOf(type: ContentType) = when (type) {
        ContentType.NOVELS -> storageManager.getLocalNovelsDirectory()
        else -> storageManager.getLocalSourceDirectory()
    }

    /**
     * Copies each group into its folder and, with [addToLibrary], adds it under [categoryIds]. A series
     * folder that exists already takes the new files beside its own, so a later volume joins its series.
     */
    suspend fun import(groups: List<ImportGroup>, addToLibrary: Boolean, categoryIds: List<Long>): ImportResult =
        withIOContext {
            val problems = mutableListOf<ImportProblem>()
            val series = groups.mapNotNull { group ->
                val root = rootOf(group.type)
                if (root == null) {
                    problems += ImportProblem(group.title, ImportProblem.Reason.NoStorage)
                    return@mapNotNull null
                }
                // A new series never lands in a folder already there, which the preview would have shown it joining.
                val folder = group.joins?.let { root.findFile(it) }
                    ?: root.createDirectory(
                        freeNames(DiskUtil.buildValidFilename(group.title)).first { root.findFile(it) == null },
                    )
                val path = folder?.name
                if (folder == null || path == null) {
                    problems += ImportProblem(group.title, ImportProblem.Reason.NoStorage)
                    return@mapNotNull null
                }
                var copied = 0
                val present = folder.listFiles().orEmpty().filter { it.isFile }
                val taken = present.mapNotNull { it.name }.toMutableSet()
                var books = present.count { it.name.orEmpty().endsWith(EPUB, ignoreCase = true) }
                group.files.forEach { file ->
                    val duplicate = present.firstOrNull { file.uri in group.duplicates && it.length() == file.size }
                    if (duplicate != null && group.onDuplicate == DuplicateChoice.Skip) return@forEach
                    val name = when {
                        duplicate != null && group.onDuplicate == DuplicateChoice.Replace -> duplicate.name.orEmpty()
                        // A book takes the series and its volume, as Rename files names it; a comic keeps its name,
                        // which names its chapter.
                        group.type == ContentType.NOVELS -> freeName(bookName(path, file, ++books), taken)
                        else -> freeName(file.name, taken)
                    }
                    runCatching { copy(file, folder, name, replacing = duplicate?.takeIf { name == it.name }) }
                        .onSuccess { copied++ }
                        .onFailure { problems += ImportProblem(file.name, ImportProblem.Reason.CopyFailed, file.uri) }
                }
                // Nothing copied, every file skipped or failed, so the series was not imported to.
                if (copied == 0) {
                    if (folder.listFiles().isNullOrEmpty()) folder.delete()
                    return@mapNotNull null
                }
                val entryId = if (addToLibrary) {
                    runCatching { add(group.type, path, categoryIds) }
                        .onFailure { problems += ImportProblem(group.title, ImportProblem.Reason.AddFailed) }
                        .getOrNull()
                } else {
                    null
                }
                ImportedSeries(group.key, group.type, group.title, path, entryId)
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
            val item = NovelItem(parsed.name ?: path, path, parsed.cover)
            if (novelAdder.addToBatch(item, LocalNovelSource.ID, categoryIds) == AddOutcome.Failed) {
                throw NotAddedException()
            }
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
            if (mangaAdder.addToBatch(stored.id, categoryIds) == AddOutcome.Failed) throw NotAddedException()
            stored.id
        }
    }

    /** "<series> - Vol. N.epub", N the book's own number or else its place in the series. */
    private fun bookName(series: String, file: ImportFile, place: Int): String {
        val number = formatChapterNumber(file.seriesIndex ?: place.toDouble())
        return epubFileName("$series - ${context.stringResource(MR.strings.volume_label, number)}")
    }

    /**
     * Copies [file] into [folder] as [name]. Over [replacing], the copy is made beside it first and takes its
     * name only once whole, so a failed copy leaves the old file, and its chapters' read state, as it was.
     */
    private fun copy(file: ImportFile, folder: UniFile, name: String, replacing: UniFile? = null) {
        val temporary = replacing?.let { freeNames("$name.part").first { folder.findFile(it) == null } }
        val target = folder.createFile(temporary ?: name) ?: throw IOException("Could not create $name")
        try {
            val input =
                context.contentResolver.openInputStream(file.uri) ?: throw IOException("Could not open ${file.name}")
            input.use { source -> target.openOutputStream().use { source.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        if (replacing != null) {
            replacing.delete()
            if (!target.renameTo(name)) throw IOException("Could not replace $name")
        }
    }

    /** Reads [file]; one whose provider gave no size takes the descriptor's, so a repeat import is spotted. */
    private fun readFile(file: ImportFile): ReadFile {
        val pfd =
            context.contentResolver.openFileDescriptor(file.uri, "r")
                ?: throw IOException("Could not open ${file.name}")
        if (pfd.statSize >= 0) {
            val sized = if (file.size < 0) file.copy(size = pfd.statSize) else file
            return pfd.use { ArchiveReader(it) }.use { read(sized, it) }
        }
        // A pipe has no size and cannot be mapped, so a streamed share is read from a copy in the cache.
        val spool = File.createTempFile("import", null, context.cacheDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> spool.outputStream().use(input::copyTo) }
            val sized = file.copy(size = spool.length())
            return ParcelFileDescriptor.open(spool, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { ArchiveReader(it) }
                .use { read(sized, it) }
        } finally {
            spool.delete()
        }
    }

    private fun read(file: ImportFile, reader: ArchiveReader): ReadFile {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val stem = file.name.substringBeforeLast('.')
        val kind = kindOf(file, extension, reader)
        // Named for its kind when its name did not say, so the local source lists the copy.
        val named = when {
            kind == "epub" && extension != "epub" -> file.copy(name = "${file.name}.epub")
            kind == "comic" && !Archive.isSupported(extension) -> file.copy(name = "${file.name}.cbz")
            else -> file
        }
        return when (kind) {
            "epub" -> {
                val archive = EpubArchive(reader::readEntries)
                val book = EpubBook.parse(archive)
                val meta = book.metadata
                ReadFile(
                    file = named.copy(seriesIndex = meta.seriesIndex.takeUnless { meta.isReikaiExport }),
                    type = if (book.isFixedLayout) ContentType.MANGA else ContentType.NOVELS,
                    series = meta.series ?: meta.title ?: stem,
                    seriesIndex = meta.seriesIndex,
                    author = meta.author,
                    cover = book.coverPath?.let { archive.read(setOf(it))[it] },
                    isEpub = true,
                )
            }
            "comic" -> {
                val comicInfo = reader.readEntries(setOf(COMIC_INFO))[COMIC_INFO]?.toString(Charsets.UTF_8)
                // The first page in reading order stands in for a cover, picked as the manga local source does.
                val firstPage = reader.useEntries { entries ->
                    entries.sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                        .find { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        ?.name
                }
                val series = comicInfo?.let { COMIC_SERIES.find(it)?.groupValues?.get(1)?.trim() }?.ifEmpty { null }
                ReadFile(
                    file = named,
                    type = ContentType.MANGA,
                    series = series ?: seriesFromStem(stem),
                    seriesIndex = null,
                    author = comicInfo?.let { COMIC_WRITER.find(it)?.groupValues?.get(1)?.trim() },
                    cover = firstPage?.let { reader.readEntries(setOf(it))[it] },
                    isEpub = false,
                )
            }
            else -> throw UnsupportedFileException()
        }
    }

    /**
     * Which kind of file this is: by its name, else by the type its provider reports, else by what the archive
     * holds, since a file shared from a cloud drive or a chat can arrive with no extension at all.
     */
    private fun kindOf(file: ImportFile, extension: String, reader: ArchiveReader): String? {
        if (extension == "epub") return "epub"
        if (Archive.isSupported(extension)) return "comic"
        when (context.contentResolver.getType(file.uri)) {
            EPUB_TYPE -> return "epub"
            in COMIC_TYPES -> return "comic"
        }
        val names = runCatching { reader.useEntries { entries -> entries.map { it.name }.toList() } }.getOrNull()
            ?: return null
        return when {
            EpubBook.looksLikeEpub(names) -> "epub"
            names.any { ImageUtil.isImage(it) } -> "comic"
            else -> null
        }
    }

    private fun readFailure(error: Throwable): ImportProblem.Reason = when {
        error is UnsupportedFileException -> ImportProblem.Reason.Unsupported
        error is EpubException && error.kind == EpubException.Kind.Protected -> ImportProblem.Reason.Protected
        else -> ImportProblem.Reason.Unreadable
    }

    private class UnsupportedFileException : IOException()

    /** The favorite write failed without throwing, so the series is not in the library. */
    private class NotAddedException : IOException()

    internal class ReadFile(
        val file: ImportFile,
        val type: ContentType,
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

        internal const val EPUB_TYPE = "application/epub+zip"

        /** The types that name a comic archive, which a plain archive's type does not. */
        internal val COMIC_TYPES = setOf(
            "application/x-cbz",
            "application/vnd.comicbook+zip",
            "application/x-cbr",
            "application/vnd.comicbook-rar",
            "application/x-cb7",
        )
        private const val COMIC_INFO = "ComicInfo.xml"
        private val COMIC_SERIES = Regex("<Series>([^<]*)</Series>")
        private val COMIC_WRITER = Regex("<Writer>([^<]*)</Writer>")

        // The marker must start a word, so the c ending "Bleach 001" stays part of the name.
        private val TRAILING_NUMBER = Regex(
            """[\s_\-.]*(?:\b(?:vol(?:ume)?|v|ch(?:apter)?|c)\.?|#)?\s*\d+(?:\.\d+)?\s*$""",
            RegexOption.IGNORE_CASE,
        )

        /** Series that differ only in case, spacing or punctuation are one series. */
        internal fun groupKey(series: String): String = series.lowercase().filter { it.isLetterOrDigit() }

        /** A comic's series from its file name: the name without a trailing volume or chapter number. */
        internal fun seriesFromStem(stem: String): String = stem.replace(TRAILING_NUMBER, "").trim().ifEmpty { stem }

        /**
         * The batch as series: files of one type whose series match by [groupKey], in volume order, each named
         * after its series, so a volume imported alone lands where its siblings do.
         */
        internal fun groupSeries(read: List<ReadFile>): List<ImportGroup> =
            read.groupBy { it.type to groupKey(it.series) }.map { (key, members) ->
                val ordered = inVolumeOrder(members, { it.file.name }) { it.seriesIndex }
                val first = ordered.first()
                ImportGroup(
                    key = "${key.first}:${key.second}",
                    type = key.first,
                    title = first.series,
                    author = first.author,
                    cover = first.cover,
                    files = ordered.map { it.file },
                    canBeEitherType = ordered.all { it.isEpub },
                )
            }
    }
}

/** Names [uri] as its provider does, made safe as a file name, since the name comes from another app. */
internal fun ContentResolver.describe(uri: Uri): ImportFile {
    var name: String? = null
    var size = -1L
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
    }
    val raw = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    return ImportFile(uri, DiskUtil.buildValidFilename(raw), size)
}
