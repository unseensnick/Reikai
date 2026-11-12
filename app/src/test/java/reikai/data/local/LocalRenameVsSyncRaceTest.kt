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
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.serialization.XML
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
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.LocalChapterStillPresentException
import reikai.domain.chapter.LocalChapterTombstones
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.Revived
import reikai.domain.chapter.SyncedChapter
import reikai.domain.library.ContentType
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.SeriesRename
import reikai.storage.FakeSafProvider
import reikai.storage.rename
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File

/**
 * The series lock covers the listing only; the sync that deletes rows runs after the source returned and the
 * lock was released. These pin what then stops a Rename files landing in that gap from losing read state:
 * the confirm step sees the renamed file and fails the refresh, and a delete by id captures the row's
 * address as it is at the delete, not as it was listed. Verifier test for the merged spikes.
 */
@RunWith(RobolectricTestRunner::class)
class LocalRenameVsSyncRaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val locks = LocalSeriesLocks().apply { open() }
    private lateinit var local: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var tombstones: LocalChapterTombstones
    private lateinit var source: LocalSource

    @Before
    fun setUp() = runTest {
        FakeSafProvider.install(context, tmp.root)
        local = tmp.newFolder("Reikai", "local")
        chapterFolder("Vol 1", "Ch 1")
        chapterFolder("Vol 1", "Ch 2")
        Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        driver.exec(
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                "($OWNER, ${LocalSource.ID}, 'Series', 'T', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
        )
        val storage = mockk<StorageManager> { every { getLocalSourceDirectory() } answers { picked(local) } }
        val fileSystem = LocalSourceFileSystem(storage)
        tombstones = LocalChapterTombstonesImpl(
            ChapterTombstoneRepositoryImpl(database),
            LocalChapterFiles(context, storage),
            NoLocalVolumes,
        )
        source = LocalSource(context, fileSystem, mockk(relaxed = true), Json, XML.v1 {}, NoLocalVolumes, locks)
    }

    @After
    fun tearDown() = driver.close()

    @Test
    fun `a rename landing between the listing and the sync fails the refresh and keeps the moved rows`() = runTest {
        sync(listed())
        markRead("Series/Vol 1/Ch 1")
        val stale = listed()

        locks.withSeries(ContentType.MANGA, "Series") { renameVolumeOnDiskAndRows("Vol 1", "Vol 2") }

        shouldThrow<LocalChapterStillPresentException> { sync(stale) }
        (readUrls() to tombstoneUrls()) shouldBe (listOf("Series/Vol 2/Ch 1") to emptyList())
    }

    /** The provider answers a failed document query "absent"; a chapter's folder listing, trusted whole, does not. */
    @Test
    fun `a present chapter whose own query fails is kept, since confirm reads its folder's listing`() = runTest {
        sync(listed())
        markRead("Series/Vol 1/Ch 2")
        File(local, "Series/Vol 1/Ch 2").deleteRecursively()
        val stale = listed()
        chapterFolder("Vol 1", "Ch 2")
        FakeSafProvider.failingDocuments += File(local, "Series/Vol 1/Ch 2")

        shouldThrow<LocalChapterStillPresentException> { sync(stale) }
        (readUrls() to tombstoneUrls()) shouldBe (listOf("Series/Vol 1/Ch 2") to emptyList())
    }

    @Test
    fun `a rename landing between the confirm and the delete tombstones the gone chapter at its new address`() =
        runTest {
            sync(listed())
            markRead("Series/Vol 1/Ch 2")
            File(local, "Series/Vol 1/Ch 2").deleteRecursively()
            val stale = listed()
            sync(stale, renamingBeforeTheWrite)

            (urls() to tombstoneUrls()) shouldBe (listOf("Series/Vol 2/Ch 1") to listOf("Series/Vol 2/Ch 2"))
        }

    @Test
    fun `the tombstone a slipped rename left at the new address revives when the file returns there`() = runTest {
        sync(listed())
        markRead("Series/Vol 1/Ch 2")
        File(local, "Series/Vol 1/Ch 2").deleteRecursively()
        sync(listed(), renamingBeforeTheWrite)
        chapterFolder("Vol 2", "Ch 2")

        sync(listed())

        (readUrls() to tombstoneUrls()) shouldBe (listOf("Series/Vol 2/Ch 2") to emptyList())
    }

    /** Renames Vol 1 to Vol 2 after the confirm and before the delete, so the gone chapter is tombstoned at Vol 2. */
    private val renamingBeforeTheWrite by lazy {
        object : LocalChapterTombstones by tombstones {
            override suspend fun <T> around(
                type: ContentType,
                ownerId: Long,
                isLocal: Boolean,
                removed: List<SyncedChapter>,
                arriving: List<SyncedChapter>,
                write: suspend () -> T,
            ): Revived<T> {
                locks.withSeries(ContentType.MANGA, "Series") { renameVolumeOnDiskAndRows("Vol 1", "Vol 2") }
                return tombstones.around(type, ownerId, isLocal, removed, arriving, write)
            }
        }
    }

    private fun chapterFolder(volume: String, chapter: String) {
        File(local, "Series/$volume/$chapter").mkdirs()
        File(local, "Series/$volume/$chapter/001.jpg").writeBytes(byteArrayOf(1))
    }

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

    private suspend fun listed(): List<SChapter> = source.getMangaUpdate(
        SManga.create().apply {
            url = "Series"
            title = "Series"
            thumbnail_url = "cover.jpg"
        },
        emptyList(),
        fetchDetails = false,
        fetchChapters = true,
    ).chapters

    private suspend fun renameVolumeOnDiskAndRows(from: String, to: String) {
        check(picked(File(local, "Series")).findFile(from)!!.rename(to))
        LocalChapterMoveRepositoryImpl(database, ChapterTombstoneRepositoryImpl(database)).renameSeries(
            ContentType.MANGA,
            SeriesRename(OWNER, "Series", "Series", mapOf(from to to)),
        )
    }

    private suspend fun sync(listed: List<SChapter>, rules: LocalChapterTombstones = tombstones) {
        val updateManga = mockk<UpdateManga> {
            coEvery { awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any(), any(), any()) } returns true
        }
        SyncChaptersWithSource(
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            chapterRepository = ChapterRepositoryImpl(database),
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            getExcludedScanlators = mockk<GetExcludedScanlators> { coEvery { await(OWNER) } returns emptySet() },
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
            chapterCopyMarks = InMemoryChapterCopyMarks(),
            localTombstones = rules,
        ).await(
            listed,
            Manga.create().copy(id = OWNER, title = "Series", source = LocalSource.ID, url = "Series"),
            mockk<Source> { every { id } returns LocalSource.ID },
        )
    }

    private suspend fun markRead(url: String) =
        driver.exec("UPDATE chapter SET user_read = 1, user_last_page_read = 3 WHERE remote_url = '$url'")

    private suspend fun urls() = driver.rows("SELECT remote_url FROM chapter ORDER BY remote_url") { it.getString(0)!! }

    private suspend fun readUrls() =
        driver.rows("SELECT remote_url FROM chapter WHERE user_read = 1 ORDER BY remote_url") { it.getString(0)!! }

    private suspend fun tombstoneUrls() =
        driver.rows("SELECT url FROM manga_chapter_tombstone WHERE read = 1 ORDER BY url") { it.getString(0)!! }

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
        const val OWNER = 1L
    }
}
