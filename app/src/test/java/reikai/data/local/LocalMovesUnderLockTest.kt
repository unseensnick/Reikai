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
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
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
import reikai.data.chapter.LocalVolumeRepositoryImpl
import reikai.data.exec
import reikai.data.novel.NovelChapterSyncRules
import reikai.data.rows
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.LocalChapterTombstones
import reikai.domain.chapter.SyncedChapter
import reikai.domain.library.ContentType
import reikai.domain.local.InMemoryLocalFileJournal
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalEntry
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.VolumeOwner
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
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every move of local files and every refresh of a local series hold the series' lock for their whole run, so a
 * refresh that has listed a series cannot sync that listing after a move has emptied or filled the folder, and
 * a move's own relist inside its hold does not wait on itself. Manga on the fake picked-folder provider; the
 * novel moves are LocalNovelVolumesTest, and the novel lock is pinned in NovelRefreshTest and LocalNovelFolderTest.
 */
@RunWith(RobolectricTestRunner::class)
class LocalMovesUnderLockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var locks: LocalSeriesLocks
    private lateinit var removal: RemoveMangaFromLibrary
    private lateinit var volumes: LocalVolumeRepositoryImpl
    private lateinit var journal: FlakyJournal
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var local: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var tombstones: PausableTombstones
    private lateinit var source: LocalSource
    private lateinit var refresh: UpdateMangaFromRemote
    private lateinit var combine: CombineLocalVolumes
    private lateinit var import: LocalImport
    private lateinit var relink: LocalSeriesRelink

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
        volumes = LocalVolumeRepositoryImpl(database, appScope)
        locks = LocalSeriesLocks(volumes).apply { open() }
        journal = FlakyJournal(LocalFileJournalRepositoryImpl(database))
        val storage = mockk<StorageManager> {
            every { getLocalSourceDirectory() } answers
                { FakeSafProvider.picked(context, local) }
        }
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
                LocalChapterFiles(context, storage),
                volumes,
            ),
        )
        // The real cover writer: a mocked one never closes the page it is handed, which on Windows pins the folder.
        val covers = LocalCoverManager(context, fileSystem)
        source = LocalSource(context, fileSystem, covers, Json, XML.v1 {}, volumes, locks)
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
        removal = mockk<RemoveMangaFromLibrary> {
            coEvery { takeOut(any()) } returns emptyMap()
            coEvery { restore(any()) } returns Unit
            coEvery { finish(any()) } returns Unit
            coEvery { await(any()) } returns emptyList()
        }
        val mangaAdder = mockk<MangaLibraryAdder> { coEvery { addToBatch(any(), any()) } returns AddOutcome.Added }
        val chapterMoves = LocalChapterMoveRepositoryImpl(database, tombstoneRepository)
        combine = CombineLocalVolumes(
            context = context,
            mangaRepository = mangaRepository,
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
            localVolumes = volumes,
            organizer = LocalOrganizer(context, journal, chapterMoves, locks, appScope).apply {
                firstRetry = 200.milliseconds
            },
            seriesLocks = locks,
            appScope = appScope,
        )
        val importJournal = InMemoryLocalFileJournal()
        import = LocalImport(
            context = context,
            storageManager = storage,
            localNovels = mockk(),
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
            libraryPreferences = libraryPreferences,
            syncRules = NovelChapterSyncRules(numberOverrides, copyMarks, tombstones),
            journal = importJournal,
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
            organizer = LocalOrganizer(context, importJournal, chapterMoves, locks, appScope),
            owners = volumes,
        )
        relink = LocalSeriesRelink(
            context = context,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
            storageManager = storage,
            chapterMoves = chapterMoves,
            seriesLocks = locks,
            owners = volumes,
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

    /** Only a novel book named like its series sits in the local folder itself; a manga volume never does. */
    @Test
    fun `deleting a manga volume named like its series deletes that volume, not the series folder`() = runTest {
        chapterFolder("Kept", "Kept", "Ch 3")
        refreshed(KEPT)

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.deleteVolumes(ContentType.MANGA, KEPT, listOf("Kept")) }
        }

        File(local, "Kept/Vol 1/Ch 1").exists() to File(local, "Kept/Kept").exists() shouldBe (true to false)
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

    /** Each its own shows a loose chapter as a volume of its own, which Delete volumes then takes by its name. */
    @Test
    fun `deleting a loose chapter shown as its own volume leaves no kept state for it, and only for it`() = runTest {
        File(local, "Kept/Ch 5").mkdirs()
        File(local, "Kept/Ch 5/001.jpg").writeBytes(byteArrayOf(5))
        refreshed(KEPT)
        markRead("Kept/Ch 5")
        tombstone(KEPT, "Kept/Ch 50")

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.deleteVolumes(ContentType.MANGA, KEPT, listOf("Ch 5")) }
        }

        tombstoneUrls(KEPT) shouldBe listOf("Kept/Ch 50")
    }

    /** A backup made after Other was combined into Kept, restored where Other is still a series of its own. */
    @Test
    fun `a restored owner row combines as a combine does`() = runTest {
        markRead("Other/Vol 1/Ch 1")

        withContext(Dispatchers.Default) {
            withTimeout(20_000) {
                combine.restoreOwners(
                    ContentType.MANGA,
                    listOf(VolumeOwner("Other", VolumeOwner.WHOLE, "Kept")),
                    emptyList(),
                )
            }
        }

        urls(KEPT) to readUrls(KEPT) shouldBe
            (listOf("Kept/Vol 1/Ch 1", "Other/Vol 1/Ch 1") to listOf("Other/Vol 1/Ch 1"))
    }

    /** A backup with no owner rows, made before Other was combined into Kept, restored without wiping. */
    @Test
    fun `a restore from before a combine folds the combined series' chapters onto the kept one`() = runTest {
        combined().finish()

        restoredFromBeforeCombine()

        urls(OTHER) to readUrls(KEPT) shouldBe (emptyList<String>() to listOf("Other/Vol 1/Ch 1"))
    }

    @Test
    fun `a restore from before a combine takes the combined series out of the library again`() = runTest {
        combined().finish()

        restoredFromBeforeCombine()

        coVerify(exactly = 2) { removal.takeOut(listOf(OTHER)) }
    }

    private suspend fun restoredFromBeforeCombine() {
        ChapterRepositoryImpl(database).updateFromRemote(
            emptyList(),
            listOf(Chapter.create().copy(mangaId = OTHER, url = "Other/Vol 1/Ch 1", name = "Ch 1", read = true)),
            emptyList(),
        )
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.restoreOwners(ContentType.MANGA, emptyList(), listOf("Kept", "Other")) }
        }
    }

    @Test
    fun `a series waiting for a repair keeps no other group of a restored backup from coming back`() = runTest {
        locks.block(ContentType.MANGA, listOf("Stuck"))

        withContext(Dispatchers.Default) { withTimeout(20_000) { runCatching { restoreWithStuck() } } }

        urls(KEPT) shouldBe listOf("Kept/Vol 1/Ch 1", "Other/Vol 1/Ch 1")
    }

    @Test
    fun `a restored group a repair holds back fails the restore phase, naming its folder`() = runTest {
        locks.block(ContentType.MANGA, listOf("Stuck"))

        val failure = withContext(Dispatchers.Default) {
            withTimeout(20_000) { runCatching { restoreWithStuck() }.exceptionOrNull() }
        }

        failure?.message.orEmpty().contains("Stuck") shouldBe true
    }

    private suspend fun restoreWithStuck() = combine.restoreOwners(
        ContentType.MANGA,
        listOf(VolumeOwner("Stuck", VolumeOwner.WHOLE, "Elsewhere"), VolumeOwner("Other", VolumeOwner.WHOLE, "Kept")),
        emptyList(),
    )

    @Test
    fun `a restored owner row takes the series it lends whole out of the library, as a combine does`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(20_000) {
                combine.restoreOwners(
                    ContentType.MANGA,
                    listOf(VolumeOwner("Other", VolumeOwner.WHOLE, "Kept")),
                    emptyList(),
                )
            }
        }

        coVerify { removal.takeOut(listOf(OTHER)) }
    }

    @Test
    fun `a combine moves no file and the kept series lists the other's chapters where they are`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        val before = files()

        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(OTHER))) }
        }

        listOf(move.result is LocalMoveResult.Moved, files() == before, urls(KEPT), readUrls(KEPT)) shouldBe
            listOf(true, true, listOf("Kept/Vol 1/Ch 1", "Other/Vol 1/Ch 1"), listOf("Other/Vol 1/Ch 1"))
    }

    @Test
    fun `a combined folder is no series of its own in Browse, and what is put there later lists under the kept one`() =
        runTest {
            withContext(Dispatchers.Default) {
                withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(OTHER))) }
            }
            chapterFolder("Other", "Vol 2", "Ch 2")

            refreshed(KEPT)
            val browsed = source.getPopularManga(1).mangas.map { it.url }

            browsed to urls(KEPT) shouldBe
                (listOf("Kept") to listOf("Kept/Vol 1/Ch 1", "Other/Vol 1/Ch 1", "Other/Vol 2/Ch 2"))
        }

    /** A chapter back on disk since the last listing has no row yet, only its kept state at its old address. */
    @Test
    fun `a chapter put back since the last listing keeps its reading state through a split of its volume`() =
        runTest {
            putBackUnlisted(KEPT, "Kept", "Vol 2", "Ch 2")

            val move = withContext(Dispatchers.Default) {
                withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
            }

            (move.result is LocalMoveResult.Moved) to allReadUrls() shouldBe (true to listOf("Kept/Vol 2/Ch 2"))
        }

    @Test
    fun `a chapter put back since the last listing keeps its reading state through a combine`() = runTest {
        putBackUnlisted(OTHER, "Other", "Vol 1", "Ch 5")

        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(OTHER))) }
        }

        (move.result is LocalMoveResult.Moved) to readUrls(KEPT) shouldBe (true to listOf("Other/Vol 1/Ch 5"))
    }

    /** A failed query reads as "no such volume", and a short listing then removes its rows as if it were gone. */
    @Test
    fun `a volume Delete volumes could not find keeps its chapters' reading state`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        markRead("Kept/Vol 2/Ch 2")
        FakeSafProvider.onCall = { call, target ->
            if (call == "queryDocument" && target == File(local, "Kept/Vol 2")) error("the provider died")
        }
        FakeSafProvider.listingFault = { FakeSafProvider.ChildListingFault.Without("Vol 2") }

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.deleteVolumes(ContentType.MANGA, KEPT, listOf("Vol 2")) }
        }
        FakeSafProvider.onCall = null
        FakeSafProvider.listingFault = null
        refreshed(KEPT)

        File(local, "Kept/Vol 2/Ch 2/001.jpg").exists() to allReadUrls() shouldBe (true to listOf("Kept/Vol 2/Ch 2"))
    }

    /** A failed query answers "not a folder", which must not make the local folder itself the series' folder. */
    @Test
    fun `deleting a volume never takes another series of the volume's name when the series' query fails`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        chapterFolder("Vol 2", "Vol 1", "Ch 1")
        refreshed(KEPT)
        var queries = 0
        FakeSafProvider.onCall = { call, target ->
            if (call == "queryDocument" && target == File(local, "Kept") && ++queries >= 2) error("the provider died")
        }

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.deleteVolumes(ContentType.MANGA, KEPT, listOf("Vol 2")) }
        }

        File(local, "Vol 2/Vol 1/Ch 1/001.jpg").exists() shouldBe true
    }

    /** The chapter had no row when the combine ran, so only an Undo that finds the volume's chapters then takes it. */
    @Test
    fun `a chapter first listed after a combine goes back with its volume on Undo, read`() = runTest {
        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(OTHER))) }
        }
        chapterFolder("Other", "Vol 1", "Ch 8")
        refreshed(KEPT)
        markRead("Other/Vol 1/Ch 8")

        val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { move.undo() } }

        (undone is LocalMoveResult.Moved) to readUrls(OTHER) shouldBe (true to listOf("Other/Vol 1/Ch 8"))
    }

    /** Found by the randomized test: the older Undo took the newer combine's rows for its own and broke the group. */
    @Test
    fun `an Undo of a combine that a later combine built on is refused and changes nothing`() = runTest {
        chapterFolder("Third", "Vol 1", "Ch 3")
        addSeries(THIRD, "Third")
        refreshed(THIRD)
        markRead("Other/Vol 1/Ch 1")
        // Other and Kept into Third, then Third and Other into Kept, then the first one's Undo.
        val first = withContext(Dispatchers.Default) {
            withTimeout(20_000) {
                combine.combine(ContentType.MANGA, series(THIRD), listOf(series(OTHER), series(KEPT)))
            }
        }
        withContext(Dispatchers.Default) {
            withTimeout(20_000) {
                combine.combine(ContentType.MANGA, series(KEPT), listOf(series(THIRD), series(OTHER)))
            }
        }
        val owners = volumes.all(ContentType.MANGA)

        val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { first.undo() } }
        refreshed(KEPT)

        listOf(undone is LocalMoveResult.Failed, volumes.all(ContentType.MANGA) == owners, readUrls(KEPT)) shouldBe
            listOf(true, true, listOf("Other/Vol 1/Ch 1"))
    }

    /** Combining a split-out series back home removes its rows and adds none, so present rows prove nothing. */
    @Test
    fun `an Undo of a combine that wrote no row is refused once a later combine moved its volumes on`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id
        val home = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(split))) }
        }
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(OTHER), listOf(series(KEPT))) }
        }
        markRead("Kept/Vol 2/Ch 2")
        val owners = volumes.all(ContentType.MANGA)

        val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { home.undo() } }
        val relisted = runCatching { refreshed(OTHER) }.isSuccess

        listOf(
            undone is LocalMoveResult.Failed,
            volumes.all(ContentType.MANGA) == owners,
            relisted,
            readUrls(OTHER),
        ) shouldBe
            listOf(true, true, true, listOf("Kept/Vol 2/Ch 2"))
    }

    /** Found by the randomized test: Other's folder lists under Kept already, with Other's chapters on Kept's rows. */
    @Test
    fun `combining a series whose folder another holds whole moves none of its chapters away from that one`() =
        runTest {
            chapterFolder("Third", "Vol 1", "Ch 3")
            addSeries(THIRD, "Third")
            refreshed(THIRD)
            markRead("Other/Vol 1/Ch 1")
            combined()

            withContext(Dispatchers.Default) {
                withTimeout(20_000) { combine.combine(ContentType.MANGA, series(THIRD), listOf(series(OTHER))) }
            }
            refreshed(KEPT)

            readUrls(KEPT) shouldBe listOf("Other/Vol 1/Ch 1")
        }

    /** Found by the randomized test: the Undo took back the split-out volume with the folder it sits in. */
    @Test
    fun `undoing a combine into a split-out series leaves it the volume it was split with`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        markRead("Kept/Vol 2/Ch 2")
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id
        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(split), listOf(series(KEPT))) }
        }

        val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { move.undo() } }

        (undone is LocalMoveResult.Moved) to readUrls(split) shouldBe (true to listOf("Kept/Vol 2/Ch 2"))
    }

    /** Found by the randomized test: once the combine was undone, the split's Undo no longer led back to it. */
    @Test
    fun `undoing a split after the combine it split from was undone hands the volume to its folder's series`() =
        runTest {
            markRead("Other/Vol 1/Ch 1")
            val joined = combined()
            val split = withContext(Dispatchers.Default) {
                withTimeout(20_000) {
                    combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Other/Vol 1"), "Split")
                }
            }
            withContext(Dispatchers.Default) { withTimeout(20_000) { joined.undo() } }

            val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { split.undo() } }

            (undone is LocalMoveResult.Moved) to readUrls(OTHER) shouldBe (true to listOf("Other/Vol 1/Ch 1"))
        }

    private suspend fun addSeries(id: Long, folder: String) = driver.exec(
        "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_cover, remote_status, " +
            "state_initialized, user_reader_flags, user_chapter_flags, user_favorite_at, " +
            "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
            "($id, ${LocalSource.ID}, '$folder', '$folder', 'cover.jpg', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
    )

    /** A split-out series has no folder, so once it leaves the library nothing else could list its volume. */
    @Test
    fun `a split-out series leaving the library hands its volume back with its reading state`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        markRead("Kept/Vol 2/Ch 2")
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id

        withContext(Dispatchers.Default) { withTimeout(20_000) { combine.handBack(ContentType.MANGA, listOf(split)) } }

        listOf(readUrls(KEPT), volumes.all(ContentType.MANGA)) shouldBe listOf(listOf("Kept/Vol 2/Ch 2"), emptyList())
    }

    /** A split-out series has no folder, so combining it lists no folder of its name: that would read as missing. */
    @Test
    fun `combining a split-out series leaves the kept one no member folder that never existed`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(OTHER), listOf(series(split))) }
        }

        volumes.all(ContentType.MANGA).map { it.folder }.distinct().sorted() shouldBe listOf("Kept")
    }

    @Test
    fun `a split makes no folder, and the split-out series lists its volume where it is`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)

        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id

        listOf(move.result is LocalMoveResult.Moved, local.list()!!.sorted(), urls(split)) shouldBe
            listOf(true, listOf("Kept", "Other"), listOf("Kept/Vol 2/Ch 2"))
    }

    // Organize files

    @Test
    fun `organize moves a combined group's volumes into its folder under the naming format, read state kept`() =
        runTest {
            markRead("Other/Vol 1/Ch 1")
            combined()
            val pages = pages()

            val move = organized()

            listOf(
                move.result is LocalMoveResult.Moved,
                pages() == pages,
                local.list()!!.sorted(),
                entries("Kept"),
                readUrls(KEPT),
                journalRows(),
            ) shouldBe listOf(
                true,
                true,
                listOf("Kept", "Other"),
                listOf("Kept - Vol. 1", "Kept - Vol. 2"),
                listOf("Kept/Kept - Vol. 2/Ch 1"),
                0L,
            )
        }

    @Test
    fun `an organize Undo puts every file and row back`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        val layout = files()

        val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { organized().undo() } }

        listOf(undone is LocalMoveResult.Moved, files() == layout, readUrls(KEPT)) shouldBe
            listOf(true, true, listOf("Other/Vol 1/Ch 1"))
    }

    @Test
    fun `an organize whose move fails part way puts back what it did, files and rows`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        val layout = files()
        FakeSafProvider.onCall = { call, _ -> if (call == "moveDocument") error("the provider refused") }

        val move = organized()
        FakeSafProvider.onCall = null

        listOf(move.result is LocalMoveResult.Failed, files() == layout, readUrls(KEPT), journalRows()) shouldBe
            listOf(true, true, listOf("Other/Vol 1/Ch 1"), 0L)
    }

    /** A process killed between a move landing and its note: the next start finishes the organize. */
    @Test
    fun `an organize a kill cut off after a move landed is finished by the replay at the next start`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        FakeSafProvider.onCall = { call, target ->
            if (call == "moveDocument") {
                FakeSafProvider.onCall = null
                // The move lands, and the process dies before anything is noted.
                File(local, "Other/${target.name}").renameTo(target)
                throw KillError()
            }
        }
        runCatching { organized() }
        FakeSafProvider.onCall = null

        val moves = LocalChapterMoveRepositoryImpl(database, ChapterTombstoneRepositoryImpl(database))
        LocalFileJournalReplay(context, journal, locks, LocalOrganizer(context, journal, moves, locks, appScope))
            .replay()
        refreshed(KEPT)

        listOf(entries("Kept"), readUrls(KEPT), journalRows()) shouldBe
            listOf(listOf("Kept - Vol. 1", "Kept - Vol. 2"), listOf("Kept/Kept - Vol. 2/Ch 1"), 0L)
    }

    /** Found by the randomized test: the Undo renamed the folder back under the series that had since taken it in. */
    @Test
    fun `an organize Undo is refused once another series lists the folder it would rename back`() = runTest {
        markRead("Kept/Vol 1/Ch 1")
        val names = combine.organizePlan(ContentType.MANGA, series(KEPT), "Saga", listOf("Vol 1" to "Vol. 1"))
        val organize = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.organizeFiles(ContentType.MANGA, series(KEPT), names) }
        }
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(OTHER), listOf(series(KEPT))) }
        }

        val undone = withContext(Dispatchers.Default) { withTimeout(20_000) { organize.undo() } }
        refreshed(OTHER)

        listOf(undone is LocalMoveResult.Failed, local.list()!!.sorted(), readUrls(OTHER)) shouldBe
            listOf(true, listOf("Other", "Saga"), listOf("Saga/Saga - Vol. 1/Ch 1"))
    }

    /** Found by the randomized test: the rows of a loose chapter the listing hid moved, and the chapter did not. */
    @Test
    fun `a loose chapter every listing hid stays put with its reading state when its group is organized`() = runTest {
        listOf("Ch 3", "Ch 4").forEach {
            File(local, "Third/$it").mkdirs()
            File(local, "Third/$it/001.jpg").writeBytes(byteArrayOf(3))
        }
        addSeries(THIRD, "Third")
        refreshed(THIRD)
        markRead("Third/Ch 4")
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(THIRD))) }
        }
        val third = File(local, "Third")
        FakeSafProvider.listingFault = { if (it == third) FakeSafProvider.ChildListingFault.Without("Ch 4") else null }
        val names = combine.organizePlan(
            ContentType.MANGA,
            series(KEPT),
            "Kept",
            listOf("Vol 1" to "1", "Third/" to "2"),
        )

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.organizeFiles(ContentType.MANGA, series(KEPT), names) }
        }
        FakeSafProvider.listingFault = null
        refreshed(KEPT)

        File(local, "Third/Ch 4/001.jpg").exists() to readUrls(KEPT) shouldBe (true to listOf("Third/Ch 4"))
    }

    /** Found by the randomized test: the row naming another folder's loose chapters went, and the hidden one with it. */
    @Test
    fun `a hidden loose chapter of a split-out series stays with it after an organize`() = runTest {
        listOf("Ch 3", "Ch 4").forEach {
            File(local, "Third/$it").mkdirs()
            File(local, "Third/$it/001.jpg").writeBytes(byteArrayOf(3))
        }
        addSeries(THIRD, "Third")
        refreshed(THIRD)
        markRead("Third/Ch 4")
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(THIRD))) }
        }
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Third/"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id
        val third = File(local, "Third")
        FakeSafProvider.listingFault = { if (it == third) FakeSafProvider.ChildListingFault.Without("Ch 4") else null }
        val names = combine.organizePlan(ContentType.MANGA, series(split), "Split", listOf("Third/" to "1"))

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.organizeFiles(ContentType.MANGA, series(split), names) }
        }
        FakeSafProvider.listingFault = null
        refreshed(split)

        readUrls(split) shouldBe listOf("Third/Ch 4")
    }

    /** A member renamed outside the app keeps its rows under its old name, which a new series there would meet. */
    @Test
    fun `a split is refused the name of a missing member's folder`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        missingMember("Gone")

        val result = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Gone") }
        }

        result.result::class shouldBe LocalMoveResult.Failed::class
    }

    @Test
    fun `organize names a series past a missing member's folder`() = runTest {
        missingMember("Gone")

        combine.organizePlan(ContentType.MANGA, series(KEPT), "Gone", emptyList()).series.to shouldBe "Gone (2)"
    }

    private suspend fun missingMember(folder: String) = driver.exec(
        "INSERT INTO local_volume_owner(content_type, folder, volume_key, owner) VALUES (0, '$folder', '/', 'Kept')",
    )

    @Test
    fun `organize is offered only where the storage can move a file`() = runTest {
        val withMove = combine.canOrganize(ContentType.MANGA, series(KEPT))
        FakeSafProvider.supportsMove = false

        (withMove to combine.canOrganize(ContentType.MANGA, series(KEPT))) shouldBe (true to false)
    }

    @Test
    fun `a split-out series organized gets a folder of its own`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        markRead("Kept/Vol 2/Ch 2")
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id
        val names = combine.organizePlan(ContentType.MANGA, series(split), "Split", listOf("Kept/Vol 2" to "Vol. 2"))

        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.organizeFiles(ContentType.MANGA, series(split), names) }
        }

        listOf(move.result is LocalMoveResult.Moved, entries("Split"), readUrls(split)) shouldBe
            listOf(true, listOf("Split - Vol. 2"), listOf("Split/Split - Vol. 2/Ch 2"))
    }

    @Test
    fun `a member folder renamed outside the app leaves its rows as they are while the group refreshes`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        File(local, "Other").renameTo(File(local, "Elsewhere"))
        chapterFolder("Kept", "Vol 2", "Ch 2")

        val result = refresh(series(KEPT).toManga(), fetchChapters = true)

        listOf(result.isSuccess, urls(KEPT), readUrls(KEPT), tombstoneUrls(KEPT)) shouldBe listOf(
            true,
            listOf("Kept/Vol 1/Ch 1", "Kept/Vol 2/Ch 2", "Other/Vol 1/Ch 1"),
            listOf("Other/Vol 1/Ch 1"),
            emptyList<String>(),
        )
    }

    @Test
    fun `a missing member linked to the folder it was renamed to keeps its reading state there`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        File(local, "Other").renameTo(File(local, "Elsewhere"))
        val candidates = relink.memberCandidates(ContentType.MANGA, KEPT, "Other")

        val linked = relink.relinkMember(ContentType.MANGA, KEPT, "Other", "Elsewhere")
        refreshed(KEPT)

        val missing = relink.missingMembers(ContentType.MANGA, KEPT)

        listOf(candidates, linked is LocalMoveResult.Moved, readUrls(KEPT), missing) shouldBe
            listOf(listOf("Elsewhere"), true, listOf("Elsewhere/Vol 1/Ch 1"), emptyList<String>())
    }

    @Test
    fun `a whole-folder member whose chapters are folders is offered the folder it was renamed to`() = runTest {
        File(local, "Delta/Ch 1").mkdirs()
        File(local, "Delta/Ch 1/001.png").writeBytes(byteArrayOf(4))
        addSeries(THIRD, "Delta")
        refreshed(THIRD)
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(THIRD))) }
        }
        File(local, "Delta").renameTo(File(local, "Delta Moved"))
        refreshed(KEPT)

        relink.memberCandidates(ContentType.MANGA, KEPT, "Delta") shouldBe listOf("Delta Moved")
    }

    @Test
    fun `a series combined in whole shows its own cover on its volume, the series' own loose files none`() = runTest {
        File(local, "Delta/Ch 1").mkdirs()
        File(local, "Delta/Ch 1/001.png").writeBytes(byteArrayOf(4))
        File(local, "Delta/cover.png").writeBytes(byteArrayOf(5))
        addSeries(THIRD, "Delta")
        refreshed(THIRD)
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(THIRD))) }
        }

        listOf(source.volumeCover("Kept", "Delta/")?.name, source.volumeCover("Kept", "")?.name) shouldBe
            listOf("cover.png", null)
    }

    @Test
    fun `a member another series also lists from is not linked from this one`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 2"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id
        File(local, "Kept").renameTo(File(local, "Elsewhere"))

        (relink.relinkMember(ContentType.MANGA, split, "Kept", "Elsewhere") is LocalMoveResult.Failed) shouldBe true
    }

    @Test
    fun `a missing member removed from the series keeps its reading state, back once its folder is`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        File(local, "Other").renameTo(File(local, "Elsewhere"))

        val removed = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.removeMember(ContentType.MANGA, series(KEPT), "Other") }
        }
        val keptAfter = urls(KEPT)
        File(local, "Elsewhere").renameTo(File(local, "Other"))
        refreshed(OTHER)

        listOf(removed is LocalMoveResult.Moved, keptAfter, readUrls(OTHER)) shouldBe
            listOf(true, listOf("Kept/Vol 1/Ch 1"), listOf("Other/Vol 1/Ch 1"))
    }

    @Test
    fun `an organize that could neither finish nor be put back is retried in the same session`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        var failing = false
        FakeSafProvider.onCall = { call, _ ->
            if (call == "moveDocument") {
                failing = true
                error("move failed")
            }
            if (failing && call == "renameDocument") error("rename failed")
        }
        val organize = organized()
        val refusedWhileBlocked = refresh(series(KEPT).toManga(), fetchChapters = true).isFailure
        FakeSafProvider.onCall = null

        val repaired = withContext(Dispatchers.Default) {
            withTimeoutOrNull(20_000) {
                while (refresh(series(KEPT).toManga(), fetchChapters = true).isFailure) delay(50)
            }
        }

        listOf(
            organize.result is LocalMoveResult.Failed,
            refusedWhileBlocked,
            repaired != null,
            readUrls(KEPT),
        ) shouldBe
            listOf(true, true, true, listOf("Other/Vol 1/Ch 1"))
    }

    @Test
    fun `a blocked organize's retry outlives a journal read that fails`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        var failing = false
        FakeSafProvider.onCall = { call, _ ->
            if (call == "moveDocument") {
                failing = true
                error("move failed")
            }
            if (failing && call == "renameDocument") error("rename failed")
        }
        organized()
        FakeSafProvider.onCall = null
        journal.failedReads = 1

        val repaired = withContext(Dispatchers.Default) {
            withTimeoutOrNull(20_000) {
                while (refresh(series(KEPT).toManga(), fetchChapters = true).isFailure) delay(50)
            }
        }

        repaired shouldNotBe null
    }

    @Test
    fun `an organize whose put-back cannot be noted fails and refuses its folders`() = runTest {
        markRead("Other/Vol 1/Ch 1")
        combined()
        FakeSafProvider.onCall = { call, _ -> if (call == "moveDocument") error("move failed") }
        journal.failsPutBackNote = true

        val organize = runCatching { organized() }
        val refused = refresh(series(KEPT).toManga(), fetchChapters = true).isFailure

        ((organize.getOrNull()?.result is LocalMoveResult.Failed) to refused) shouldBe (true to true)
    }

    /** A row written before the root is reached would be replayed later, carrying out an organize reported failed. */
    @Test
    fun `an organize whose local folder cannot be reached fails with no journal row and no folder refused`() = runTest {
        combined()
        val names = combine.organizePlan(
            ContentType.MANGA,
            series(KEPT),
            "Kept",
            listOf("Vol 1" to "Vol. 1", "Other/Vol 1" to "Vol. 2"),
        )
        // The organizer's first look at the root, after the plan has been read whole.
        var hiccups = 1
        FakeSafProvider.onCall = { call, target ->
            val byOrganizer = Thread.currentThread().stackTrace.any {
                it.className.startsWith(LocalOrganizer::class.qualifiedName!!)
            }
            if (call == "queryDocument" && target == local && byOrganizer && hiccups-- > 0) error("the provider died")
        }

        val move = withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.organizeFiles(ContentType.MANGA, series(KEPT), names) }
        }
        FakeSafProvider.onCall = null
        val refused = refresh(series(KEPT).toManga(), fetchChapters = true).isFailure

        listOf(move.result is LocalMoveResult.Failed, journalRows(), refused) shouldBe listOf(true, 0L, false)
    }

    /** Found by the randomized test: a split-out volume the organize's listing missed whole lost its owner row. */
    @Test
    fun `a borrowed volume the organize's listing missed stays with the series that lists it`() = runTest {
        chapterFolder("Kept", "Vol 2", "Ch 2")
        refreshed(KEPT)
        markRead("Kept/Vol 1/Ch 1")
        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.splitOut(ContentType.MANGA, series(KEPT), listOf("Vol 1", "Vol 2"), "Split") }
        }
        val split = MangaRepositoryImpl(database).getMangaByUrlAndSourceId("Split", LocalSource.ID)!!.id
        val vol1 = File(local, "Kept/Vol 1")
        FakeSafProvider.listingFault = { if (it == vol1) FakeSafProvider.ChildListingFault.Without("Ch 1") else null }
        val names = combine.organizePlan(
            ContentType.MANGA,
            series(split),
            "Split",
            listOf("Kept/Vol 1" to "1", "Kept/Vol 2" to "2"),
        )

        withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.organizeFiles(ContentType.MANGA, series(split), names) }
        }
        FakeSafProvider.listingFault = null
        refresh(series(KEPT).toManga(), fetchChapters = true)

        urls(KEPT) shouldBe emptyList()
    }

    private suspend fun combined() = withContext(Dispatchers.Default) {
        withTimeout(20_000) { combine.combine(ContentType.MANGA, series(KEPT), listOf(series(OTHER))) }
    }

    private suspend fun organized(): LocalVolumeMove {
        val labels = listOf("Vol 1" to "Vol. 1", "Other/Vol 1" to "Vol. 2")
        val names = combine.organizePlan(ContentType.MANGA, series(KEPT), "Kept", labels)
        return withContext(Dispatchers.Default) {
            withTimeout(20_000) { combine.organizeFiles(ContentType.MANGA, series(KEPT), names) }
        }
    }

    /** Every page under the local folder, in hidden folders too, by its path and bytes; marker files left out. */
    private fun files() = local.walkTopDown()
        .filter { it.isFile && it.name != "cover.jpg" && !it.name.startsWith('.') }
        .associate { it.relativeTo(local).invariantSeparatorsPath to it.readBytes().toList() }

    /** Every page's bytes wherever it is, so a move shows as the same pages and a loss as one fewer. */
    private fun pages() = files().values.sortedBy { it.toString() }

    /** What series folder [series] holds besides its cover and the markers a listing writes. */
    private fun entries(series: String) =
        File(local, series).list()!!.filterNot { it.startsWith('.') || it.startsWith("cover.") }.sorted()

    private suspend fun journalRows() = driver.rows("SELECT count(*) FROM local_file_journal") { it.getLong(0)!! }
        .single()

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

    /** A read chapter whose folder went, was listed gone, and is back without a listing since. */
    private suspend fun putBackUnlisted(owner: Long, series: String, volume: String, chapter: String) {
        chapterFolder(series, volume, chapter)
        refreshed(owner)
        markRead("$series/$volume/$chapter")
        File(local, "$series/$volume/$chapter").deleteRecursively()
        refreshed(owner)
        chapterFolder(series, volume, chapter)
    }

    private suspend fun allReadUrls() =
        driver.rows("SELECT remote_url FROM chapter WHERE user_read = 1 ORDER BY remote_url") { it.getString(0)!! }

    private fun chapterFolder(series: String, volume: String, chapter: String) {
        File(local, "$series/$volume/$chapter").mkdirs()
        File(local, "$series/$volume/$chapter/001.jpg").writeBytes(byteArrayOf(1))
    }

    private suspend fun series(id: Long) = combine.series(ContentType.MANGA, listOf(id)).single()

    private suspend fun CombinableSeries.toManga() = MangaRepositoryImpl(database).getMangaById(id)

    private suspend fun refreshed(id: Long) = refresh(series(id).toManga(), fetchChapters = true).getOrThrow()

    private suspend fun markRead(url: String) =
        driver.exec("UPDATE chapter SET user_read = 1, user_last_page_read = 3 WHERE remote_url = '$url'")

    private suspend fun urls(owner: Long) =
        driver.rows("SELECT remote_url FROM chapter WHERE manga_id = $owner ORDER BY remote_url") { it.getString(0)!! }

    private suspend fun tombstone(owner: Long, url: String) = driver.exec(
        "INSERT INTO manga_chapter_tombstone(manga_id, url, read, bookmark, last_page_read, " +
            "page_count, date_fetch, removed_at) VALUES ($owner, '$url', 1, 0, 0, 0, 0, 0)",
    )

    private suspend fun tombstoneUrls(owner: Long) =
        driver.rows("SELECT url FROM manga_chapter_tombstone WHERE manga_id = $owner ORDER BY url") {
            it.getString(0)!!
        }

    private suspend fun readUrls(owner: Long) = driver.rows(
        "SELECT remote_url FROM chapter WHERE manga_id = $owner AND user_read = 1 ORDER BY remote_url",
    ) { it.getString(0)!! }

    private companion object {
        const val KEPT = 1L
        const val OTHER = 2L
        const val THIRD = 3L
    }
}

/** The real journal, with a database that fails the reads and the put-back note a test asks it to. */
private class FlakyJournal(private val real: LocalFileJournal) : LocalFileJournal by real {
    var failedReads = 0
    var failsPutBackNote = false

    override suspend fun all(): List<LocalFileJournalEntry> {
        if (failedReads > 0) {
            failedReads--
            error("database busy")
        }
        return real.all()
    }

    override suspend fun update(id: Long, plan: LocalFileJournalPlan) {
        if (failsPutBackNote && (plan as? LocalFileJournalPlan.Organize)?.isPutBack == true) {
            failsPutBackNote = false
            error("database busy")
        }
        real.update(id, plan)
    }
}
