package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.inUserOrder
import reikai.domain.library.ContentType
import reikai.source.local.localVolumeChapter
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.comicinfo.setLocalLanguage
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.fillMetadata
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.source.model.Source as DomainSource

@Inject
@SingleIn(AppScope::class)
class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    private val json: Json,
    private val xml: XML,
    private val userVolumes: LocalVolumeRepository, // RK
) : Source, UnmeteredSource {

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val name: String = context.stringResource(MR.strings.local_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchManga(page, "", LatestFilters)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }

        var mangaDirs = fileSystem.getFilesInBaseDirectory()
            // Filter out files that are hidden and is not a folder
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') && it.name !in SYSTEM_FOLDERS } // RK
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    } else {
                        mangaDirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    }
                }
                is OrderBy.Latest -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedBy(UniFile::lastModified)
                    } else {
                        mangaDirs.sortedByDescending(UniFile::lastModified)
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        val mangas = mangaDirs
            .map { mangaDir ->
                async {
                    SManga.create().apply {
                        title = mangaDir.name.orEmpty()
                        url = mangaDir.name.orEmpty()

                        // Try to find the cover
                        coverManager.find(mangaDir.name.orEmpty())?.let {
                            thumbnail_url = it.uri.toString()
                        }
                    }
                }
            }
            .awaitAll()

        MangasPage(mangas, false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
        val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    // Manga details related
    private suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        coverManager.find(manga.url)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            val mangaDir = fileSystem.getMangaDirectory(manga.url) ?: error("${manga.url} is not a valid directory")
            val mangaDirFiles = mangaDir.listFiles().orEmpty()

            val comicInfoFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_FILE }
            val noXmlFile = mangaDirFiles
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = mangaDirFiles
                .firstOrNull { it.extension == "json" }

            when {
                // Top level ComicInfo.xml
                comicInfoFile != null -> {
                    noXmlFile?.delete()
                    setMangaDetailsFromComicInfoFile(comicInfoFile.openInputStream(), manga)
                }

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                legacyJsonDetailsFile != null -> {
                    json.decodeFromStream<MangaDetails>(legacyJsonDetailsFile.openInputStream()).run {
                        title?.let { manga.title = it }
                        author?.let { manga.author = it }
                        artist?.let { manga.artist = it }
                        description?.let { manga.description = it }
                        genre?.let { manga.genre = it.joinToString() }
                        status?.let { manga.status = it }
                        language?.let { manga.setLocalLanguage(it) } // RK
                    }
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    mangaDir
                        .createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            legacyJsonDetailsFile.delete()
                        }
                }

                // Copy ComicInfo.xml from chapter archive to top level if found
                noXmlFile == null -> {
                    val chapterArchives = mangaDirFiles.filter(Archive::isSupported)

                    val copiedFile = copyComicInfoFileFromChapters(chapterArchives, mangaDir)
                    if (copiedFile != null) {
                        setMangaDetailsFromComicInfoFile(copiedFile.openInputStream(), manga)
                    } else {
                        // Avoid re-scanning
                        mangaDir.createFile(".noxml")
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withIOContext manga
    }

    private fun <T> getComicInfoForChapter(chapter: UniFile, block: (InputStream) -> T): T? {
        return if (chapter.isDirectory) {
            chapter.findFile(COMIC_INFO_FILE)?.openInputStream()?.use(block)
        } else {
            chapter.archiveReader(context).use { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use(block)
            }
        }
    }

    private fun copyComicInfoFileFromChapters(chapterArchives: List<UniFile>, folder: UniFile): UniFile? {
        for (chapter in chapterArchives) {
            val file = getComicInfoForChapter(chapter) f@{ stream ->
                return@f copyComicInfoFile(stream, folder)
            }
            if (file != null) return file
        }
        return null
    }

    private fun copyComicInfoFile(comicInfoFileStream: InputStream, folder: UniFile): UniFile? {
        return folder.createFile(COMIC_INFO_FILE)?.apply {
            openOutputStream().use { outputStream ->
                comicInfoFileStream.use { it.copyTo(outputStream) }
            }
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        return AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        manga.copyFromComicInfo(parseComicInfo(stream))
    }

    private fun setChapterDetailsFromComicInfoFile(stream: InputStream, chapter: SChapter) {
        val comicInfo = parseComicInfo(stream)

        comicInfo.title?.let { chapter.name = it.value }
        comicInfo.number?.value?.toFloatOrNull()?.let { chapter.chapter_number = it }
        comicInfo.translator?.let { chapter.scanlator = it.value }

        // only bother with partial dates if the year is not null, abandon date parsing otherwise
        val year = comicInfo.year?.value?.takeIf { it > 0 }?.toString()?.padStart(4, '0') ?: return
        val month = (comicInfo.month?.value?.coerceIn(1, 12) ?: 1).toString().padStart(2, '0')
        val day = (comicInfo.day?.value?.coerceIn(1, 31) ?: 1).toString().padStart(2, '0')

        val dateInstant = try {
            LocalDate.parse("$year-$month-$day").atStartOfDayIn(TimeZone.currentSystemDefault())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
        dateInstant?.let { chapter.date_upload = it.toEpochMilliseconds() }
    }

    // Chapters
    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        // RK: a folder that cannot be listed fails the refresh, as a local novel's does, since an empty list
        // deletes every chapter row of the series and its read state with it
        val chapters = listedOrFail(fileSystem.getMangaDirectory(manga.url), manga.url)
            // Only keep supported formats
            .filter(::isChapterFile) // RK: shared with the volume folders below
            // RK --> a folder holding chapter files or chapter folders is a volume, read one level down; its
            // chapters carry it in their address and in front of their name, as a local novel's volumes do
            .flatMap { file ->
                val path = "${manga.url}/${file.name}"
                val listed = if (file.isDirectory) listedOrFail(file, path) else emptyList()
                // A folder of pages holding folders of pictures (credits, a group's extras) is one chapter, as
                // upstream reads it: a volume holds a chapter archive, or no pages of its own beside its cover.
                val inside = listed.filter { isVolumeChapter(it, "$path/${it.name}") }
                    .takeIf { chapters -> chapters.any { !it.isFolderName() } || listed.extraPictures().isEmpty() }
                    .orEmpty()
                // Pictures beside a volume's chapters, other than its cover, read as one Extras chapter: the
                // folder itself, which the reader opens as a folder of pictures.
                val extras = listOfNotNull(file.takeIf { inside.isNotEmpty() && listed.extraPictures().isNotEmpty() })
                if (inside.isNotEmpty()) (extras + inside).map { file to it } else listOf(null to file)
            }
            .map { (volume, chapterFile) ->
                val isExtras = chapterFile === volume
                SChapter.create().apply {
                    url = if (isExtras) {
                        "${manga.url}/${volume.name}/"
                    } else {
                        listOfNotNull(manga.url, volume?.name, chapterFile.name).joinToString("/")
                    }
                    // RK <--
                    name = if (chapterFile.isDirectory) {
                        chapterFile.name
                    } else {
                        chapterFile.nameWithoutExtension
                    }.orEmpty()
                    date_upload = chapterFile.lastModified()
                    chapter_number = ChapterRecognition
                        .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                        .toFloat()

                    try {
                        val format = Format.valueOf(chapterFile)
                        if (format is Format.Epub) {
                            format.file.epubReader(context).use { epub ->
                                epub.fillMetadata(manga, this)
                            }
                        } else {
                            getComicInfoForChapter(chapterFile) { stream ->
                                setChapterDetailsFromComicInfoFile(stream, this)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to read metadata for ${chapterFile.name}" }
                    }
                    // RK --> after the metadata, which may name the chapter, so the volume order survives the sort
                    if (isExtras) name = context.stringResource(MR.strings.local_volume_extras)
                    volume?.let { name = "${it.name} - $name" }
                    // RK <--
                }
            }
            .sortedWith { c1, c2 ->
                c2.name.compareToCaseInsensitiveNaturalOrder(c1.name)
            }
            .inUserVolumeOrder(manga.url) // RK
            .numberingExtras() // RK
        // RK: no chapter at all is also how a picked folder's failed listing reads, so the refresh fails rather
        // than deleting every row, as a local novel with no book does
        if (chapters.isEmpty()) throw NoChaptersException()

        // Copy the cover from the first chapter found if not available
        if (manga.thumbnail_url.isNullOrBlank()) {
            chapters.lastOrNull()?.let { chapter ->
                updateCover(chapter, manga)
            }
        }

        chapters
    }

    // Filters
    override fun getFilterList() = FilterList(OrderBy.Popular(context))

    // Unused stuff
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Unused")

    fun getFormat(chapter: SChapter): Format {
        try {
            // RK: one step per path part, since a chapter in a volume folder sits two levels down
            return chapter.url.split('/').filter { it.isNotEmpty() }
                .fold(fileSystem.getBaseDirectory()) { dir, part -> dir?.findFile(part) }
                ?.let(Format.Companion::valueOf)
                ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        } catch (e: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        } catch (e: Exception) {
            throw e
        }
    }

    // RK --> volume folders
    // In the order the user gave them, each led by its Extras, still newest first like the rest of the list.
    private suspend fun List<SChapter>.inUserVolumeOrder(series: String): List<SChapter> {
        val saved = userVolumes.loadedVolumes(ContentType.MANGA)[series]
        return asReversed().groupBy { localVolumeChapter(it.url)?.volume.orEmpty() }
            .entries.toList().inUserOrder({ it.key }, saved)
            .flatMap { (_, chapters) -> chapters.sortedByDescending { it.url.endsWith('/') } }.asReversed()
    }

    // An Extras chapter has no number of its own, so it takes one just below its volume's first, which keeps it
    // at the head of its volume under a sort by chapter number too.
    private fun List<SChapter>.numberingExtras(): List<SChapter> = onEach { extras ->
        if (!extras.url.endsWith('/')) return@onEach
        val first = filter { it.url.startsWith(extras.url) && it !== extras && it.chapter_number >= 0f }
            .minOfOrNull { it.chapter_number } ?: return@onEach
        extras.chapter_number = first - 0.01f
    }

    /** A volume folder's cover: a picture named cover or folder, or its only picture. */
    fun volumeCover(series: String, volume: String): UniFile? {
        val pictures = fileSystem.getMangaDirectory(series)?.findFile(volume)?.listFiles().orEmpty().filter {
            it.isPicture()
        }
        return pictures.firstOrNull { it.isNamedCover() } ?: pictures.singleOrNull()
    }

    private fun List<UniFile>.extraPictures(): List<UniFile> {
        val pictures = filter { it.isPicture() }
        val cover = pictures.firstOrNull { it.isNamedCover() } ?: pictures.singleOrNull()
        return pictures - setOfNotNull(cover)
    }

    // By name alone, as a picture-named folder is never a chapter: each isDirectory is a query on a picked folder.
    private fun UniFile.isPicture() = ImageUtil.isImage(name)

    private fun UniFile.isNamedCover() = name.orEmpty().substringBeforeLast('.').lowercase() in setOf("cover", "folder")
    // RK <--

    // RK: a chapter's own file or folder, in a series folder or a volume folder; never a folder a system keeps
    // The name checks run first: on a picked folder each isDirectory is a query, and a chapter holds many pages.
    private fun isChapterFile(file: UniFile): Boolean =
        !file.name.orEmpty().startsWith('.') &&
            (
                !file.isFolderName() ||
                    (!ImageUtil.isImage(file.name) && file.name !in SYSTEM_FOLDERS && file.isDirectory)
                )

    // RK: an archive or a book, by its name alone, so no folder query is spent on one
    private fun UniFile.isFolderName() = !Archive.isSupported(this) && !extension.equals("epub", true)

    // RK: a chapter inside a volume folder. A folder there counts only with a picture or an archive in it, or
    // a chapter of pictures holding any other folder would read as a volume and lose its own chapter row.
    private fun isVolumeChapter(file: UniFile, path: String): Boolean = isChapterFile(file) &&
        (
            !file.isDirectory || listedOrFail(file, path).any {
                !it.name.orEmpty().startsWith('.') && (it.isPicture() || Archive.isSupported(it))
            }
            )

    // RK: [dir]'s files, or a failed refresh when it is missing or its listing fails, which an empty list would
    // read as every chapter deleted
    private fun listedOrFail(dir: UniFile?, path: String): List<UniFile> =
        dir?.listFiles()?.toList() ?: throw Exception(context.stringResource(MR.strings.local_folder_unreadable, path))

    private fun updateCover(chapter: SChapter, manga: SManga): UniFile? {
        return try {
            when (val format = getFormat(chapter)) {
                is Format.Directory -> {
                    val entry = format.file.listFiles()
                        ?.sortedWith { f1, f2 ->
                            f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(
                                f2.name.orEmpty(),
                            )
                        }
                        ?.find {
                            !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
                        }

                    entry?.let { coverManager.update(manga, it.openInputStream()) }
                }
                is Format.Archive -> {
                    format.file.archiveReader(context).use { reader ->
                        val entry = reader.useEntries { entries ->
                            entries
                                .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                                .find { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        }

                        entry?.let { coverManager.update(manga, reader.getInputStream(it.name)!!) }
                    }
                }
                is Format.Epub -> {
                    format.file.epubReader(context).use { epub ->
                        val entry = epub.getImagesFromPages().firstOrNull()

                        entry?.let { coverManager.update(manga, epub.getInputStream(it)!!) }
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error updating cover for ${manga.title}" }
            null
        }
    }

    companion object {
        // RK: folders macOS, Synology and Windows leave beside files, which hold no chapter
        private val SYSTEM_FOLDERS =
            setOf("__MACOSX", "@eaDir", "#recycle", "\$RECYCLE.BIN", "System Volume Information")

        const val ID = 0L
        const val HELP_URL = "${Constants.URL_DOCS}/guides/local-source/" // RK: Reikai's docs

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds
    }
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

fun Source.isLocal(): Boolean = id == LocalSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalSource.ID
