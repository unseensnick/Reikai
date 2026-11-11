package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import mihon.domain.source.interactor.UpdateMangaFromRemote
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.chapter.LocalChapterTombstonesImpl
import reikai.data.novel.NovelChapterSyncRules
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.LocalChapterTombstones
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.SyncedChapter
import reikai.domain.library.ContentType
import reikai.domain.local.InMemoryLocalFileJournal
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.MangaLibraryAdder
import reikai.storage.FakeSafProvider
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File

/**
 * Every move of local files and every refresh of a local series hold the series' lock for their whole run, so a
 * refresh that has listed a series cannot sync that listing after a move has emptied or filled the folder, and
 * a move's own relist inside its hold does not wait on itself. Manga on the fake picked-folder provider; the
 * novel half shares the engine and pins its lock in NovelRefreshTest and LocalNovelFolderTest.
 */
@RunWith(RobolectricTestRunner::class)
class LocalMovesUnderLockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val locks = LocalSeriesLocks().apply { open() }
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var local: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var tombstones: PausableTombstones
    private lateinit var source: LocalSource
    private lateinit var refresh: UpdateMangaFromRemote
    private lateinit var combine: CombineLocalVolumes
    private lateinit var import: LocalImport

    @Before
    fun setUp() = runTest {
        FakeSafProvider.install(context, tmp.root)
        Robolectric.setupContentProvider(ImportDocuments::class.java, ImportDocuments.AUTHORITY)
        local = tmp.newFolder("Reikai", "local")
        chapterFolder("Kept", "Vol 1", "Ch 1")
        chapterFolder("Other", "Vol 1", "Ch 1")
        Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        // With a cover already, a listing opens no page to copy one, which on Windows would pin the folder.
        listOf(KEPT to "Kept", OTHER to "Other").forEach { (id, folder) ->
            driver.exec(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_cover, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($id, ${LocalSource.ID}, '$folder', '$folder', 'cover.jpg', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
            )
        }
        val storage = mockk<StorageManager> { every { getLocalSourceDirectory() } answers { picked(local) } }
        val fileSystem = LocalSourceFileSystem(storage)
        val mangaRepository = MangaRepositoryImpl(database)
        val chapterRepository = ChapterRepositoryImpl(database)
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        val numberOverrides = ChapterNumberOverrideRepositoryImpl(database)
        val copyMarks = InMemoryChapterCopyMarks()
        val tombstoneRepository = ChapterTombstoneRepositoryImpl(database)
        tombstones = PausableTombstones(
            LocalChapterTombstonesImpl(
                tombstoneRepository,
                LocalChapterFiles(context, fileSystem, storage),
            ),
        )
        source = LocalSource(context, fileSystem, mockk(relaxed = true), Json, XML.v1 {}, NoLocalVolumes, locks)
        val sync = SyncChaptersWithSource(
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
        )
        refresh = UpdateMangaFromRemote(
            sourceManager = mockk<SourceManager> { coEvery { getOrStub(LocalSource.ID) } returns source },
            chapterRepository = chapterRepository,
            mangaRepository = mangaRepository,
            syncChaptersWithSource = sync,
            coverCache = mockk(relaxed = true),
            libraryPreferences = libraryPreferences,
            downloadManager = mockk(relaxed = true),
            seriesLocks = locks,
        )
        val removal = mockk<RemoveMangaFromLibrary> {
            coEvery { takeOut(any()) } returns emptyMap()
            coEvery { restore(any()) } returns Unit
            coEvery { dropCovers(any()) } returns Unit
            coEvery { await(any()) } returns emptyList()
        }
        val mangaAdder = mockk<MangaLibraryAdder> { coEvery { addToBatch(any(), any()) } returns AddOutcome.Added }
        val chapterMoves = LocalChapterMoveRepositoryImpl(database, tombstoneRepository)
        combine = CombineLocalVolumes(
            context = context,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            localFiles = fileSystem,
            localManga = source,
            localNovels = mockk(),
            updateMangaFromRemote = refresh,
            removeManga = removal,
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
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
            mangaAdder = mangaAdder,
            novelAdder = mockk(),
            setEntryTitle = mockk(),
            localVolumes = NoLocalVolumes,
            journal = InMemoryLocalFileJournal(),
            seriesLocks = locks,
            appScope = appScope,
        )
        import = LocalImport(
            context = context,
            storageManager = storage,
            localNovels = mockk(),
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
            libraryPreferences = libraryPreferences,
            syncRules = NovelChapterSyncRules(numberOverrides, copyMarks, tombstones),
            journal = InMemoryLocalFileJournal(),
            novelAdder = mockk(),
            mangaAdder = mangaAdder,
            networkToLocalManga = NetworkToLocalManga(mangaRepository),
            // Stands in for the refresh an import ends with, which would read the imported archive's metadata
            // through the device's native library; it writes the row that refresh would.
            updateMangaFromRemote = mockk {
                coEvery { this@mockk.invoke(any<Manga>(), any(), any(), any(), any()) } coAnswers {
                    val manga = firstArg<Manga>()
                    val added = Chapter.create().copy(mangaId = manga.id, url = "${manga.url}/Ch 2.cbz", name = "Ch 2")
                    chapterRepository.updateFromRemote(emptyList(), listOf(added), emptyList())
                    Result.success(mockk())
                }
            },
            seriesLocks = locks,
        )
        refreshed(KEPT)
        refreshed(OTHER)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        driver.close()
    }

    @Test
    fun `a refresh of the leaving series that has listed it holds the combine back, so no row is left behind`() =
        runTest {
            markRead("Other/Vol 1/Ch 1")
            tombstones.pauseNext()
            val refreshing = async(Dispatchers.IO) { refreshed(OTHER) }
            tombstones.listed.await()
            val combining =
                async(Dispatchers.IO) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(OTHER))) }
            // Real time: a combine nothing holds back has long finished before the paused sync resumes.
            val combinedWhilePaused = withContext(Dispatchers.Default) { withTimeoutOrNull(1500) { combining.await() } }
            tombstones.go.complete(Unit)
            refreshing.await()
            val move = withContext(Dispatchers.Default) { withTimeout(20_000) { combining.await() } }
            val otherAfterCombine = urls(OTHER)

            val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { move.undo() } }

            listOf(
                combinedWhilePaused == null,
                move.result is LocalMoveResult.Moved,
                otherAfterCombine,
                undone is LocalMoveResult.Moved,
                readUrls(OTHER),
            ) shouldBe listOf(true, true, emptyList<String>(), true, listOf("Other/Vol 1/Ch 1"))
        }

    @Test
    fun `an import into a series that is being refreshed waits for the refresh, which then succeeds`() = runTest {
        ImportDocuments.documents["1"] = ImportDocuments.Document("Ch 2.cbz", 3L, null, "abc".toByteArray())
        val group = ImportGroup(
            key = "kept",
            type = ContentType.MANGA,
            title = "Kept",
            author = null,
            cover = null,
            files = listOf(ImportFile(ImportDocuments.uri("1"), "Ch 2.cbz", 3L)),
            canBeEitherType = false,
            existing = "Kept",
        )
        tombstones.pauseNext()
        val refreshing = async(Dispatchers.IO) { refresh(series(KEPT).toManga(), fetchChapters = true) }
        tombstones.listed.await()
        val importing = async(Dispatchers.IO) { import.import(listOf(group), addToLibrary = true, emptyList()) }
        val importedWhilePaused = withContext(Dispatchers.Default) { withTimeoutOrNull(1500) { importing.await() } }
        tombstones.go.complete(Unit)

        val result = refreshing.await()
        val imported = withContext(Dispatchers.Default) { withTimeout(20_000) { importing.await() } }

        listOf(importedWhilePaused == null, result.isSuccess, imported.problems, urls(KEPT)) shouldBe
            listOf(true, true, emptyList<ImportProblem>(), listOf("Kept/Ch 2.cbz", "Kept/Vol 1/Ch 1"))
    }

    @Test
    fun `a split and its undo list the series they hold without waiting on themselves`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)

        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
        }
        val afterSplit = urls(KEPT)
        val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { move.undo() } }

        listOf(move.result is LocalMoveResult.Moved, afterSplit, undone is LocalMoveResult.Moved, urls(KEPT)) shouldBe
            listOf(true, listOf("Kept/Vol 1/Ch 1"), true, listOf("Kept/Vol 1/Ch 1", "Kept/Vol 2/Ch 2"))
    }

    @Test
    fun `deleting volumes lists the series it holds without waiting on itself`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)

        val result = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.deleteVolumes(ContentType.MANGA, KEPT, listOf("Vol 2")) }
        }

        (result is LocalMoveResult.Moved) to urls(KEPT) shouldBe (true to listOf("Kept/Vol 1/Ch 1"))
    }

    @Test
    fun `deleting a volume leaves no kept state for its chapters, read or lost before`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        markRead("Kept/Vol 2/Ch 2")
        // A chapter of the volume whose file went before: kept state with no row.
        tombstone(KEPT, "Kept/Vol 2/Ch 9")

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.deleteVolumes(ContentType.MANGA, KEPT, listOf("Vol 2")) }
        }

        urls(KEPT) to tombstoneUrls(KEPT) shouldBe (listOf("Kept/Vol 1/Ch 1") to emptyList())
    }

    @Test
    fun `a volume joining the kept series drops the kept state waiting under its new name`() = runTest {
        // Other's "Vol 1" lands in Kept as "Vol 1 (2)", a name a volume deleted outside the app left state under.
        tombstone(KEPT, "Kept/Vol 1 (2)/Ch 9")

        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(OTHER))) }
        }

        listOf(move.result is LocalMoveResult.Moved, urls(KEPT), tombstoneUrls(KEPT)) shouldBe
            listOf(true, listOf("Kept/Vol 1 (2)/Ch 1", "Kept/Vol 1/Ch 1"), emptyList<String>())
    }

    /** The real rules, with one call of [confirm] held between its listing and its sync when asked. */
    private class PausableTombstones(private val real: LocalChapterTombstones) : LocalChapterTombstones by real {
        var listed = CompletableDeferred<Unit>()
        var go = CompletableDeferred<Unit>()
        private var pausing = false

        fun pauseNext() {
            listed = CompletableDeferred()
            go = CompletableDeferred()
            pausing = true
        }

        override suspend fun confirm(
            type: ContentType,
            isLocal: Boolean,
            removed: List<SyncedChapter>,
            listed: Collection<String>,
        ) {
            if (pausing) {
                pausing = false
                this.listed.complete(Unit)
                go.await()
            }
            real.confirm(type, isLocal, removed, listed)
        }
    }

    private fun chapterFolder(series: String, volume: String, chapter: String) {
        File(local, "$series/$volume/$chapter").mkdirs()
        File(local, "$series/$volume/$chapter/001.jpg").writeBytes(byteArrayOf(1))
    }

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

    private suspend fun series(id: Long) = combine.series(ContentType.MANGA, listOf(id)).single()

    private suspend fun CombinableSeries.toManga() = MangaRepositoryImpl(database).getMangaById(id)

    private suspend fun refreshed(id: Long) = refresh(series(id).toManga(), fetchChapters = true).getOrThrow()

    private suspend fun markRead(url: String) =
        driver.exec("UPDATE chapter SET user_read = 1, user_last_page_read = 3 WHERE remote_url = '$url'")

    private suspend fun urls(owner: Long) =
        driver.rows("SELECT remote_url FROM chapter WHERE manga_id = $owner ORDER BY remote_url") { it.getString(0)!! }

    private suspend fun tombstone(owner: Long, url: String) = driver.exec(
        "INSERT INTO manga_chapter_tombstone(manga_id, url, chapter_number, read, bookmark, last_page_read, " +
            "page_count, date_fetch, removed_at) VALUES ($owner, '$url', 9.0, 1, 0, 0, 0, 0, 0)",
    )

    private suspend fun tombstoneUrls(owner: Long) =
        driver.rows("SELECT url FROM manga_chapter_tombstone WHERE manga_id = $owner ORDER BY url") {
            it.getString(0)!!
        }

    private suspend fun readUrls(owner: Long) = driver.rows(
        "SELECT remote_url FROM chapter WHERE manga_id = $owner AND user_read = 1 ORDER BY remote_url",
    ) { it.getString(0)!! }

    private suspend fun JdbcSqliteDriver.exec(sql: String) {
        execute(null, sql, 0).await()
    }

    private suspend fun <T> JdbcSqliteDriver.rows(sql: String, map: (SqlCursor) -> T): List<T> = executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(map(cursor))
                },
            )
        },
        0,
    ).await()

    private companion object {
        const val KEPT = 1L
        const val OTHER = 2L
    }
}
