package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.chapter.LocalChapterTombstonesImpl
import reikai.data.chapter.LocalVolumeRepositoryImpl
import reikai.data.exec
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelChapterSyncRules
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.refreshNovelFromSource
import reikai.data.rows
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.library.ContentType
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.VolumeOwner
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.novel.download.NovelDownloadManager
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import reikai.novel.local.epub.opf
import reikai.novel.local.epub.xhtml
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.AddOutcome
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.storage.FakeSafProvider
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Combine, split, Delete volumes and Organize files on local novels, through the real novel source, repositories
 * and engine over the fake picked-folder provider. The novel half of LocalMovesUnderLockTest, whose manga cases
 * these mirror; a novel's volume is a book, and its chapter addresses are series, book and place in the book.
 */
@RunWith(RobolectricTestRunner::class)
class LocalNovelVolumesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val takenOut = mutableListOf<Long>()
    private lateinit var root: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var novels: NovelRepositoryImpl
    private lateinit var chapters: NovelChapterRepositoryImpl
    private lateinit var source: LocalNovelSource
    private lateinit var refresh: suspend (Long) -> Unit
    private lateinit var combine: CombineLocalVolumes

    @Before
    fun setUp() = runTest {
        openBooksInMemory()
        FakeSafProvider.install(context, tmp.root)
        root = tmp.newFolder("Reikai", "novels")
        book("Kept/Kept 1.epub")
        book("Other/Other 1.epub")
        Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        addSeries(KEPT, "Kept")
        addSeries(OTHER, "Other")
        val volumes = LocalVolumeRepositoryImpl(database, appScope)
        val locks = LocalSeriesLocks(volumes).apply { open() }
        val storage = mockk<StorageManager> {
            every { getLocalNovelsDirectory() } answers { FakeSafProvider.picked(context, root) }
            every { getLocalSourceDirectory() } returns null
        }
        novels = NovelRepositoryImpl(database)
        chapters = NovelChapterRepositoryImpl(database)
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        val tombstoneRepository = ChapterTombstoneRepositoryImpl(database)
        val syncRules = NovelChapterSyncRules(
            ChapterNumberOverrideRepositoryImpl(database),
            InMemoryChapterCopyMarks(),
            LocalChapterTombstonesImpl(tombstoneRepository, LocalChapterFiles(context, storage), volumes),
        )
        source = LocalNovelSource(context, storage, Json, mockk(relaxed = true), volumes, locks)
        // Stores the new title as the real manager does around its folder move; nothing is downloaded here.
        val downloads = mockk<NovelDownloadManager>(relaxed = true) {
            coEvery { renameNovel(any(), any(), any()) } coAnswers { thirdArg<suspend () -> Boolean>()() }
        }
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
        val removal = mockk<RemoveNovelsFromLibrary> {
            coEvery { takeOut(any()) } answers {
                takenOut += firstArg<List<Long>>()
                emptyMap()
            }
            coEvery { restore(any()) } returns Unit
            coEvery { finish(any()) } returns Unit
            coEvery { await(any()) } returns emptyList()
        }
        val chapterMoves = LocalChapterMoveRepositoryImpl(database, tombstoneRepository)
        combine = CombineLocalVolumes(
            context = context,
            mangaRepository = mockk(),
            localManga = mockk(),
            localNovels = source,
            updateMangaFromRemote = mockk(),
            removeManga = mockk(),
            novelRepository = novels,
            novelChapterRepository = chapters,
            storageManager = storage,
            novelSources = novelSources,
            libraryPreferences = libraryPreferences,
            syncRules = syncRules,
            tombstones = tombstoneRepository,
            coverCache = mockk(relaxed = true),
            novelDownloadManager = { downloads },
            removeNovels = removal,
            chapterMoves = chapterMoves,
            networkToLocalManga = mockk(),
            mangaAdder = mockk(),
            novelAdder = mockk<NovelLibraryAdder> {
                coEvery { addToBatch(any(), any(), any()) } returns AddOutcome.Added
            },
            setEntryTitle = mockk(relaxed = true),
            localVolumes = volumes,
            organizer = LocalOrganizer(
                context,
                LocalFileJournalRepositoryImpl(database),
                chapterMoves,
                locks,
                appScope,
            ).apply {
                firstRetry = 200.milliseconds
            },
            seriesLocks = locks,
            appScope = appScope,
        )
        refresh(KEPT)
        refresh(OTHER)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        driver.close()
        unmockkStatic("mihon.core.archive.UniFileExtensionsKt")
    }

    @Test
    fun `a combine lists the other series' books under the kept one, read state kept, and the other leaves`() =
        runTest {
            markRead("Other", "Other 1.epub")

            val move = onApp { combine.combine(ContentType.NOVELS, series(KEPT), listOf(series(OTHER))) }

            listOf(move.result is LocalMoveResult.Moved, books(KEPT), readBooks(KEPT), books(OTHER), takenOut) shouldBe
                listOf(
                    true,
                    listOf("Kept/Kept 1.epub", "Other/Other 1.epub"),
                    listOf("Other/Other 1.epub"),
                    emptyList<String>(),
                    listOf(OTHER),
                )
        }

    @Test
    fun `a combine's Undo gives the other series its books back, read`() = runTest {
        markRead("Other", "Other 1.epub")
        val move = onApp { combine.combine(ContentType.NOVELS, series(KEPT), listOf(series(OTHER))) }

        val undone = onApp { move.undo() }

        listOf(undone is LocalMoveResult.Moved, books(KEPT), readBooks(OTHER)) shouldBe
            listOf(true, listOf("Kept/Kept 1.epub"), listOf("Other/Other 1.epub"))
    }

    @Test
    fun `a split makes a local novel series that lists the book where it is, read state carried`() = runTest {
        book("Kept/Kept 2.epub")
        refresh(KEPT)
        markRead("Kept", "Kept 2.epub")

        val move = onApp { combine.splitOut(ContentType.NOVELS, series(KEPT), listOf("Kept 2.epub"), "Split") }
        val split = novels.getByUrlAndSource("Split", LocalNovelSource.ID)!!.id

        listOf(move.created == split, root.list()!!.sorted(), books(KEPT), readBooks(split)) shouldBe
            listOf(true, listOf("Kept", "Other"), listOf("Kept/Kept 1.epub"), listOf("Kept/Kept 2.epub"))
    }

    /** A split-out series has no folder, so once it leaves the library nothing else could list its book. */
    @Test
    fun `a split-out novel series leaving the library hands its book back with its reading state`() = runTest {
        book("Kept/Kept 2.epub")
        refresh(KEPT)
        markRead("Kept", "Kept 2.epub")
        onApp { combine.splitOut(ContentType.NOVELS, series(KEPT), listOf("Kept 2.epub"), "Split") }
        val split = novels.getByUrlAndSource("Split", LocalNovelSource.ID)!!.id

        onApp { combine.handBack(ContentType.NOVELS, listOf(split)) }

        readBooks(KEPT) to driver.rows("SELECT count(*) FROM local_volume_owner") { it.getLong(0)!! } shouldBe
            (listOf("Kept/Kept 2.epub") to listOf(0L))
    }

    @Test
    fun `only the local novels in the library are offered to take in`() = runTest {
        driver.exec(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                "VALUES (7, 'plugin', 'Online', 'Online', 0, 0, 0, 0)",
        )
        driver.exec(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags) " +
                "VALUES (8, '${LocalNovelSource.ID}', 'Gone', 'Gone', 0, 0, 0)",
        )

        combine.library(ContentType.NOVELS).map { it.id }.sorted() shouldBe listOf(KEPT, OTHER)
    }

    /** A backup made after Other was combined into Kept, restored where Other is still a series of its own. */
    @Test
    fun `a restored owner row combines novels as a combine does`() = runTest {
        markRead("Other", "Other 1.epub")

        onApp {
            combine.restoreOwners(
                ContentType.NOVELS,
                listOf(VolumeOwner("Other", VolumeOwner.WHOLE, "Kept")),
                emptyList(),
            )
        }

        books(KEPT) to readBooks(KEPT) shouldBe
            (listOf("Kept/Kept 1.epub", "Other/Other 1.epub") to listOf("Other/Other 1.epub"))
    }

    /** A backup with no owner rows, made before Other was combined into Kept, restored without wiping. */
    @Test
    fun `a restore from before a combine folds the novels' chapters onto the kept series and keeps it out`() =
        runTest {
            onApp { combine.combine(ContentType.NOVELS, series(KEPT), listOf(series(OTHER))) }.finish()
            val url = urls(KEPT, readOnly = false).single { it.startsWith("Other") }
            driver.exec(
                "INSERT INTO novel_chapters(novel_id, url, name, read, bookmark, chapter_number, source_order, " +
                    "date_fetch) VALUES ($OTHER, '$url', 'c1', 1, 0, 1.0, 0, 0)",
            )

            onApp { combine.restoreOwners(ContentType.NOVELS, emptyList(), listOf("Kept", "Other")) }

            Triple(books(OTHER), readBooks(KEPT), takenOut) shouldBe
                Triple(emptyList(), listOf("Other/Other 1.epub"), listOf(OTHER, OTHER))
        }

    @Test
    fun `deleting a book leaves no kept state for its chapters, read or lost before`() = runTest {
        book("Kept/Kept 2.epub")
        refresh(KEPT)
        markRead("Kept", "Kept 2.epub")
        // A chapter of the book that left it before: kept state with no row.
        tombstone(KEPT, LocalNovelSource.chapterPath("Kept", "Kept 2.epub", EpubChapterKey("OEBPS/gone.xhtml")))

        val result = onApp { combine.deleteVolumes(ContentType.NOVELS, KEPT, listOf("Kept 2.epub")) }

        listOf(result is LocalMoveResult.Moved, File(root, "Kept/Kept 2.epub").exists(), books(KEPT), tombstones(KEPT))
            .shouldBe(listOf(true, false, listOf("Kept/Kept 1.epub"), emptyList<String>()))
    }

    /** What the kept state left behind would cost: the chapters of a book put back under its name come back read. */
    @Test
    fun `a book put back under a deleted book's name comes back unread`() = runTest {
        book("Kept/Kept 2.epub")
        refresh(KEPT)
        markRead("Kept", "Kept 2.epub")
        onApp { combine.deleteVolumes(ContentType.NOVELS, KEPT, listOf("Kept 2.epub")) }
        book("Kept/Kept 2.epub")

        refresh(KEPT)

        books(KEPT) to readBooks(KEPT) shouldBe (listOf("Kept/Kept 1.epub", "Kept/Kept 2.epub") to emptyList())
    }

    /** A lone EPUB in the local folder is a series of one, and Organize renames that book rather than foldering it. */
    @Test
    fun `organizing a lone book renames it in place, read state kept`() = runTest {
        val lone = lone()

        val move = organizeLone(lone)

        listOf(move.result is LocalMoveResult.Moved, root.list()!!.sorted(), readBooks(lone)) shouldBe
            listOf(true, listOf("Kept", "Other", "Saga.epub"), listOf("Saga.epub/Saga.epub"))
    }

    @Test
    fun `an organize Undo puts a lone book and its read state back`() = runTest {
        val lone = lone()
        val move = organizeLone(lone)

        val undone = onApp { move.undo() }

        listOf(undone is LocalMoveResult.Moved, root.list()!!.sorted(), readBooks(lone)) shouldBe
            listOf(true, listOf("Kept", "Lone.epub", "Other"), listOf("Lone.epub/Lone.epub"))
    }

    /** A loose book with a read chapter, listed as its own series. */
    private suspend fun lone(): Long {
        book("Lone.epub")
        addSeries(LONE, "Lone.epub")
        refresh(LONE)
        markRead("Lone.epub", "Lone.epub")
        return LONE
    }

    private suspend fun organizeLone(id: Long): LocalVolumeMove {
        val series = series(id)
        val names = combine.organizePlan(ContentType.NOVELS, series, "Saga", listOf("Lone.epub" to "1"))
        return onApp { combine.organizeFiles(ContentType.NOVELS, series, names) }
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

    private suspend fun <T> onApp(work: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(20_000) { work() } }

    private fun book(path: String) {
        File(root, path).apply { parentFile!!.mkdirs() }.writeBytes(path.toByteArray())
    }

    private suspend fun addSeries(id: Long, url: String) = driver.exec(
        "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
            "VALUES ($id, '${LocalNovelSource.ID}', '$url', '$url', 0, 0, 0, 0)",
    )

    private suspend fun series(id: Long) = combine.series(ContentType.NOVELS, listOf(id)).single()

    /** Each chapter row's series folder and book, which is what a volume move changes. */
    private suspend fun books(owner: Long, readOnly: Boolean = false) = urls(owner, readOnly).map { url ->
        LocalNovelSource.parseChapterPath(url)!!.let { (folder, book) -> "$folder/$book" }
    }

    private suspend fun readBooks(owner: Long) = books(owner, readOnly = true)

    private suspend fun urls(owner: Long, readOnly: Boolean) = driver.rows(
        "SELECT url FROM novel_chapters WHERE novel_id = $owner${if (readOnly) " AND read = 1" else ""} ORDER BY url",
    ) { it.getString(0)!! }

    /** Marks read every chapter row of [book] in [folder], matched by parsing the address back. */
    private suspend fun markRead(folder: String, book: String) =
        driver.rows("SELECT url FROM novel_chapters") { it.getString(0)!! }
            .filter { LocalNovelSource.parseChapterPath(it)?.let { (f, b) -> f to b } == folder to book }
            .forEach { driver.exec("UPDATE novel_chapters SET read = 1, last_text_progress = 3 WHERE url = '$it'") }

    private suspend fun tombstone(owner: Long, url: String) = driver.exec(
        "INSERT INTO novel_chapter_tombstone(novel_id, url, chapter_number, read, bookmark, last_text_progress, " +
            "date_fetch, removed_at) VALUES ($owner, '$url', 9.0, 1, 0, 0, 0, 0)",
    )

    private suspend fun tombstones(owner: Long) =
        driver.rows("SELECT url FROM novel_chapter_tombstone WHERE novel_id = $owner ORDER BY url") {
            it.getString(0)!!
        }

    private companion object {
        const val KEPT = 1L
        const val OTHER = 2L
        const val LONE = 3L
    }
}
