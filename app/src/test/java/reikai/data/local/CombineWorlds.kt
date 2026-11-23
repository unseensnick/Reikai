package reikai.data.local

import android.content.Context
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import mihon.domain.source.interactor.UpdateMangaFromRemote
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.rules.TemporaryFolder
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.chapter.LocalChapterTombstonesImpl
import reikai.data.chapter.LocalVolumeRepositoryImpl
import reikai.data.exec
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelChapterSyncRules
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.library.ContentType
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.VolumeOwner
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelDownloadManager
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.opf
import reikai.novel.local.epub.xhtml
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.storage.FakeSafProvider
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * The volumes repository, whose notes writes can be made to fail from the [failNotesAfter]th one on, and whose
 * one owner read [failRead] answers true for, given the rows it would have read, fails.
 */
internal class FlakyNotes(private val real: LocalVolumeRepository) : LocalVolumeRepository by real {
    var failNotesAfter: Int? = null
    var failRead: ((List<VolumeOwner>) -> Boolean)? = null

    override suspend fun all(type: ContentType): List<VolumeOwner> = real.all(type).also { rows ->
        if (failRead?.invoke(rows) == true) {
            failRead = null
            error("Reading volumes failed")
        }
    }

    override suspend fun setNotes(type: ContentType, series: String, key: String, notes: String) {
        val left = failNotesAfter
        if (left != null) {
            if (left <= 0) error("Writing notes failed")
            failNotesAfter = left - 1
        }
        real.setNotes(type, series, key, notes)
    }
}

/**
 * Local series of one content type on the fake picked-folder provider with a real database and engine, each
 * folder a series of its own with one volume, so one conformance test drives a combine for manga and novels alike.
 * Built after `virtualDispatchers()`, so its app scope and every IO hop run on the test's scheduler.
 */
internal abstract class CombineWorld(val type: ContentType, protected val context: Context) {
    protected val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    protected lateinit var driver: JdbcSqliteDriver
    protected lateinit var database: Database
    lateinit var volumes: LocalVolumeRepositoryImpl
    lateinit var notesWrites: FlakyNotes
    lateinit var combine: CombineLocalVolumes
    lateinit var import: LocalImport

    /** Builds the world with series [ids] (id to folder), each with its first volume, listed. */
    abstract suspend fun open(tmp: TemporaryFolder, ids: Map<Long, String>)

    /** The key of volume [n] in [folder]'s own series. */
    abstract fun volume(folder: String, n: Int): String

    /** Puts volume [n] in [folder] and lists series [id] again. */
    abstract suspend fun addVolume(id: Long, folder: String, n: Int)

    abstract suspend fun setSeriesNotes(id: Long, text: String)

    abstract suspend fun seriesNotes(id: Long): String

    /** Series [folder]'s folder on disk. */
    abstract fun folderOf(folder: String): File

    abstract suspend fun description(id: Long): String?

    open suspend fun close() {
        appScope.coroutineContext.job.cancelAndJoin()
        driver.close()
    }

    suspend fun series(id: Long) = combine.series(type, listOf(id)).single()

    /** [series]' saved volume rows, by key. */
    suspend fun rows(series: String) = volumes.loadedVolumes(type)[series].orEmpty().associateBy { it.key }

    /** [series]' volumes that have notes, by key. */
    suspend fun notes(series: String) = rows(series).mapNotNull { (key, row) -> row.notes?.let { key to it } }.toMap()

    /** Makes the next Undo find the volumes changed since, so it fails. */
    suspend fun changeOwnersBehindUndo() = driver.exec("DELETE FROM local_volume_owner")

    /** [work] on the app, which runs on the test's virtual dispatchers; a stall fails by name. */
    suspend fun <T> onApp(work: suspend () -> T): T = withTimeout(20_000) { work() }

    protected suspend fun openDatabase() {
        Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        volumes = LocalVolumeRepositoryImpl(database, appScope)
        notesWrites = FlakyNotes(volumes)
    }
}

internal class MangaCombineWorld(context: Context) : CombineWorld(ContentType.MANGA, context) {
    private lateinit var local: File
    private lateinit var refresh: UpdateMangaFromRemote
    private lateinit var mangaRepository: MangaRepositoryImpl

    override suspend fun open(tmp: TemporaryFolder, ids: Map<Long, String>) {
        FakeSafProvider.install(context, tmp.root)
        local = tmp.newFolder("Reikai", "local")
        openDatabase()
        val locks = LocalSeriesLocks(volumes).apply { open() }
        val storage = mockk<StorageManager> {
            every { getLocalSourceDirectory() } answers { FakeSafProvider.picked(context, local) }
        }
        val fileSystem = LocalSourceFileSystem(storage)
        mangaRepository = MangaRepositoryImpl(database)
        val chapterRepository = ChapterRepositoryImpl(database)
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        val numberOverrides = ChapterNumberOverrideRepositoryImpl(database)
        val copyMarks = InMemoryChapterCopyMarks()
        val tombstoneRepository = ChapterTombstoneRepositoryImpl(database)
        val tombstones = LocalChapterTombstonesImpl(tombstoneRepository, LocalChapterFiles(context, storage), volumes)
        // The real cover writer: a mocked one never closes the page it is handed, which on Windows pins the folder.
        val source =
            LocalSource(context, fileSystem, LocalCoverManager(context, fileSystem), Json, XML.v1 {}, volumes, locks)
        refresh = UpdateMangaFromRemote(
            sourceManager = mockk<SourceManager> { coEvery { getOrStub(LocalSource.ID) } returns source },
            chapterRepository = chapterRepository,
            mangaRepository = mangaRepository,
            syncChaptersWithSource = SyncChaptersWithSource(
                downloadManager = mockk(relaxed = true),
                downloadProvider = mockk(relaxed = true),
                chapterRepository = chapterRepository,
                shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                updateManga = mockk<UpdateManga>(relaxed = true) {
                    coEvery { awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any(), any(), any()) } returns true
                },
                getExcludedScanlators = mockk<GetExcludedScanlators> { coEvery { await(any()) } returns emptySet() },
                libraryPreferences = libraryPreferences,
                chapterNumberOverrides = numberOverrides,
                chapterCopyMarks = copyMarks,
                localTombstones = tombstones,
            ),
            coverCache = mockk(relaxed = true),
            libraryPreferences = libraryPreferences,
            downloadManager = mockk(relaxed = true),
            seriesLocks = locks,
        )
        val chapterMoves = LocalChapterMoveRepositoryImpl(database, tombstoneRepository)
        import = LocalImport(
            context = context,
            storageManager = storage,
            localNovels = mockk(),
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
            libraryPreferences = libraryPreferences,
            syncRules = NovelChapterSyncRules(numberOverrides, copyMarks, tombstones),
            journal = LocalFileJournalRepositoryImpl(database),
            novelAdder = mockk(),
            mangaAdder = mockk(),
            networkToLocalManga = NetworkToLocalManga(mangaRepository),
            updateMangaFromRemote = refresh,
            seriesLocks = locks,
            organizer = LocalOrganizer(
                context,
                LocalFileJournalRepositoryImpl(database),
                chapterMoves,
                locks,
                appScope,
            ),
            owners = volumes,
            xml = XML.v1 {},
            coverCache = mockk(relaxed = true),
            novelDownloadManager = { mockk() },
        )
        combine = CombineLocalVolumes(
            context = context,
            mangaRepository = mangaRepository,
            localManga = source,
            localNovels = mockk(),
            updateMangaFromRemote = refresh,
            removeManga = mockk<RemoveMangaFromLibrary> {
                coEvery { takeOut(any()) } returns emptyMap()
                coEvery { restore(any()) } returns Unit
                coEvery { finish(any()) } returns Unit
                coEvery { await(any()) } returns emptyList()
            },
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
            chapterRepository = chapterRepository,
            storageManager = storage,
            novelSources = mockk(),
            libraryPreferences = libraryPreferences,
            syncRules = NovelChapterSyncRules(numberOverrides, copyMarks, tombstones),
            tombstones = tombstoneRepository,
            coverCache = mockk(relaxed = true),
            novelDownloadManager = { mockk() },
            removeNovels = mockk(),
            chapterMoves = chapterMoves,
            networkToLocalManga = NetworkToLocalManga(mangaRepository),
            mangaAdder = mockk<MangaLibraryAdder> { coEvery { addToBatch(any(), any()) } returns AddOutcome.Added },
            novelAdder = mockk(),
            setEntryTitle = mockk(),
            localVolumes = notesWrites,
            organizer = LocalOrganizer(
                context,
                LocalFileJournalRepositoryImpl(database),
                chapterMoves,
                locks,
                appScope,
            ).apply { firstRetry = 200.milliseconds },
            seriesLocks = locks,
            appScope = appScope,
        )
        ids.forEach { (id, folder) ->
            // With a cover already, a listing opens no page to copy one, which on Windows would pin the folder.
            driver.exec(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_cover, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($id, ${LocalSource.ID}, '$folder', '$folder', 'cover.jpg', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
            )
            addVolume(id, folder, 1)
        }
    }

    override fun volume(folder: String, n: Int) = "Vol $n"

    override suspend fun addVolume(id: Long, folder: String, n: Int) {
        page("$folder/Vol $n/Ch $n")
        relist(id)
    }

    /** Puts chapter [name] loose in [folder] and lists series [id] again. */
    suspend fun addLoose(id: Long, folder: String, name: String) {
        page("$folder/$name")
        relist(id)
    }

    override suspend fun setSeriesNotes(id: Long, text: String) =
        driver.exec("UPDATE manga SET user_notes = '$text' WHERE id = $id")

    override suspend fun seriesNotes(id: Long) = mangaRepository.getMangaById(id).notes

    override fun folderOf(folder: String) = File(local, folder)

    override suspend fun description(id: Long) = mangaRepository.getMangaById(id).description

    private fun page(chapter: String) {
        File(local, chapter).mkdirs()
        File(local, "$chapter/001.jpg").writeBytes(byteArrayOf(1))
    }

    private suspend fun relist(id: Long) {
        refresh(mangaRepository.getMangaById(id), fetchChapters = true).getOrThrow()
    }
}

internal class NovelCombineWorld(context: Context) : CombineWorld(ContentType.NOVELS, context) {
    private lateinit var root: File
    private lateinit var refresh: suspend (Long) -> Unit
    private lateinit var novels: NovelRepositoryImpl

    override suspend fun open(tmp: TemporaryFolder, ids: Map<Long, String>) {
        openBooksInMemory()
        FakeSafProvider.install(context, tmp.root)
        root = tmp.newFolder("Reikai", "novels")
        openDatabase()
        val locks = LocalSeriesLocks(volumes).apply { open() }
        val storage = mockk<StorageManager> {
            every { getLocalNovelsDirectory() } answers { FakeSafProvider.picked(context, root) }
            every { getLocalSourceDirectory() } returns null
        }
        novels = NovelRepositoryImpl(database)
        val chapters = NovelChapterRepositoryImpl(database)
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        val tombstoneRepository = ChapterTombstoneRepositoryImpl(database)
        val syncRules = NovelChapterSyncRules(
            ChapterNumberOverrideRepositoryImpl(database),
            InMemoryChapterCopyMarks(),
            LocalChapterTombstonesImpl(tombstoneRepository, LocalChapterFiles(context, storage), volumes),
        )
        val source = LocalNovelSource(context, storage, Json, mockk(relaxed = true), volumes, locks)
        val downloads = mockk<NovelDownloadManager>(relaxed = true)
        val novelSources = mockk<NovelSourceManager>()
        coEvery { novelSources.get(LocalNovelSource.ID) } returns source
        refresh = { id ->
            refreshNovelFromSource(
                novels.getById(id)!!,
                source,
                chapters,
                novels,
                libraryPreferences,
                syncRules,
                mockk(relaxed = true),
                downloads,
                manualFetch = true,
            )
        }
        val chapterMoves = LocalChapterMoveRepositoryImpl(database, tombstoneRepository)
        import = LocalImport(
            context = context,
            storageManager = storage,
            localNovels = source,
            novelRepository = novels,
            novelChapterRepository = chapters,
            libraryPreferences = libraryPreferences,
            syncRules = syncRules,
            journal = LocalFileJournalRepositoryImpl(database),
            novelAdder = mockk(),
            mangaAdder = mockk(),
            networkToLocalManga = mockk(),
            updateMangaFromRemote = mockk(),
            seriesLocks = locks,
            organizer = LocalOrganizer(
                context,
                LocalFileJournalRepositoryImpl(database),
                chapterMoves,
                locks,
                appScope,
            ),
            owners = volumes,
            xml = XML.v1 {},
            coverCache = mockk(relaxed = true),
            novelDownloadManager = { downloads },
        )
        combine = CombineLocalVolumes(
            context = context,
            mangaRepository = mockk(),
            localManga = mockk(),
            localNovels = source,
            updateMangaFromRemote = mockk(),
            removeManga = mockk(),
            novelRepository = novels,
            novelChapterRepository = chapters,
            chapterRepository = mockk(),
            storageManager = storage,
            novelSources = novelSources,
            libraryPreferences = libraryPreferences,
            syncRules = syncRules,
            tombstones = tombstoneRepository,
            coverCache = mockk(relaxed = true),
            novelDownloadManager = { downloads },
            removeNovels = mockk<RemoveNovelsFromLibrary> {
                coEvery { takeOut(any()) } returns emptyMap()
                coEvery { restore(any()) } returns Unit
                coEvery { finish(any()) } returns Unit
                coEvery { await(any()) } returns emptyList()
            },
            chapterMoves = chapterMoves,
            networkToLocalManga = mockk(),
            mangaAdder = mockk(),
            novelAdder = mockk<NovelLibraryAdder> {
                coEvery { addToBatch(any(), any(), any()) } returns AddOutcome.Added
            },
            setEntryTitle = mockk(relaxed = true),
            localVolumes = notesWrites,
            organizer = LocalOrganizer(
                context,
                LocalFileJournalRepositoryImpl(database),
                chapterMoves,
                locks,
                appScope,
            ).apply { firstRetry = 200.milliseconds },
            seriesLocks = locks,
            appScope = appScope,
        )
        ids.forEach { (id, folder) ->
            driver.exec(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($id, '${LocalNovelSource.ID}', '$folder', '$folder', 0, 0, 0, 0)",
            )
            addVolume(id, folder, 1)
        }
    }

    override fun volume(folder: String, n: Int) = "$folder $n.epub"

    override suspend fun addVolume(id: Long, folder: String, n: Int) {
        val path = "$folder/${volume(folder, n)}"
        File(root, path).apply { parentFile!!.mkdirs() }.writeBytes(path.toByteArray())
        refresh(id)
    }

    override suspend fun setSeriesNotes(id: Long, text: String) {
        novels.update(NovelUpdate(id) { notes = text })
    }

    override suspend fun seriesNotes(id: Long) = novels.getById(id)!!.notes

    override fun folderOf(folder: String) = File(root, folder)

    override suspend fun description(id: Long) = novels.getById(id)!!.description

    override suspend fun close() {
        super.close()
        unmockkStatic("mihon.core.archive.UniFileExtensionsKt")
    }

    // The JVM has no native archive library, so every book opens as one small in-memory book of one chapter.
    private fun openBooksInMemory() {
        val entries = mapOf(
            "META-INF/container.xml" to """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""",
            "OEBPS/content.opf" to opf(listOf("c1" to "c1.xhtml")),
            "OEBPS/c1.xhtml" to xhtml("<p>Text</p>"),
        ).mapValues { it.value.toByteArray() }
        mockkStatic("mihon.core.archive.UniFileExtensionsKt")
        every { any<UniFile>().archiveReader(any()) } answers {
            mockk<ArchiveReader>(relaxUnitFun = true) {
                every { readEntries(any()) } answers { entries.filterKeys { it in firstArg<Set<String>>() } }
            }
        }
    }
}
