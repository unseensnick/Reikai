package reikai.domain.chapter

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.chapter.LocalChapterTombstonesImpl
import reikai.data.local.LocalChapterFiles
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelChapterSyncRules
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.domain.library.ContentType
import reikai.novel.host.ChapterItem
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import kotlin.time.measureTime

/**
 * A local refresh keeps a removed chapter's reading state until its file comes back, for both local
 * sources, through each type's real sync over a real database and a real folder: the removal is
 * confirmed against the files, the state is kept in the write's own transaction and given back in the
 * write that stores the returning chapter. Under Robolectric only for the folder: unifile reads a
 * plain file through android.text.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ChapterTombstoneConformanceTest(private val type: Type) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File get() = tmp.root
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var tombstones: LocalChapterTombstones

    @Before
    fun setUp() = runTest {
        // DriverManager loads its drivers once per JVM, from the first caller's class loader. Under the
        // Robolectric sandbox that copy of org.sqlite.JDBC is invisible to the plain JDBC tests that run
        // after this one in the same worker, so the system loader's copy is registered first.
        Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        val storage = mockk<StorageManager> {
            every { getLocalSourceDirectory() } returns UniFile.fromFile(File(root, "local").apply { mkdirs() })
            every { getLocalNovelsDirectory() } returns UniFile.fromFile(File(root, "localnovels").apply { mkdirs() })
        }
        tombstones = LocalChapterTombstonesImpl(
            ChapterTombstoneRepositoryImpl(database),
            LocalChapterFiles(LocalSourceFileSystem(storage), storage),
        )
    }

    @After
    fun tearDown() = driver.close()

    @Test
    fun `a chapter removed by one refresh comes back with its state when its file returns`() = runTest {
        type.seed(driver, local = true)
        type.synced(type.listed(1), type.listed(2))
        type.setState(driver, type.url(2))
        type.file(root, type.url(2)).delete()
        type.synced(type.listed(1))
        type.file(root, type.url(2)).writeText("back")

        val returned = type.synced(type.listed(1), type.listed(2))

        listOf(type.stateOf(driver, type.url(2)), returned, type.tombstoneCount(driver)) shouldBe
            listOf(type.marked, Synced(foundRelease = false, newUrls = emptyList()), 0L)
    }

    @Test
    fun `a chapter whose file is still there is kept and the refresh fails`() = runTest {
        type.seed(driver, local = true)
        type.synced(type.listed(1), type.listed(2))

        shouldThrow<LocalChapterStillPresentException> { type.synced(type.listed(1)) }

        (type.rowCount(driver) to type.tombstoneCount(driver)) shouldBe (2L to 0L)
    }

    @Test
    fun `a chapter dropped from a container the refresh still reads is confirmed gone`() = runTest {
        type.seed(driver, local = true)
        val (before, after) = type.container(root)
        type.synced(*before.toTypedArray())

        type.synced(*after.toTypedArray())

        (type.rowCount(driver) to type.tombstoneCount(driver)) shouldBe (1L to 1L)
    }

    @Test
    fun `a different chapter at a new url takes no state`() = runTest {
        type.seed(driver, local = true)
        type.synced(type.listed(1), type.listed(2))
        type.setState(driver, type.url(2))
        type.file(root, type.url(2)).delete()
        type.synced(type.listed(1))

        type.synced(type.listed(1), type.listed(3))

        (type.stateOf(driver, type.url(3)) to type.tombstoneCount(driver)) shouldBe (UNREAD to 1L)
    }

    @Test
    fun `a url differing only by case takes the state`() = runTest {
        type.seed(driver, local = true)
        type.synced(type.listed(1), type.listed(2))
        type.setState(driver, type.url(2))
        type.file(root, type.url(2)).delete()
        type.synced(type.listed(1))

        val returned = type.synced(type.listed(1), Listed(type.urlInOtherCase(2), 2.0))

        (type.stateOf(driver, type.urlInOtherCase(2)) to returned.newUrls) shouldBe (type.marked to emptyList())
    }

    @Test
    fun `of two urls differing only by case, the exact one takes the state`() = runTest {
        type.seed(driver, local = true)
        type.synced(type.listed(1), type.listed(2))
        type.setState(driver, type.url(2))
        type.file(root, type.url(2)).delete()
        type.synced(type.listed(1))

        type.synced(type.listed(1), Listed(type.urlInOtherCase(2), 2.0), type.listed(2))

        (type.stateOf(driver, type.url(2)) to type.stateOf(driver, type.urlInOtherCase(2))) shouldBe
            (type.marked to UNREAD)
    }

    @Test
    fun `the one chapter arriving under a removed chapter's number takes its whole state`() = runTest {
        type.seed(driver, local = true)
        type.synced(type.listed(1), type.listed(2))
        type.setState(driver, type.url(2))
        type.file(root, type.url(2)).delete()

        type.synced(type.listed(1), Listed(type.url(3), 2.0))

        (type.stateOf(driver, type.url(3)) to type.tombstoneCount(driver)) shouldBe (type.marked to 0L)
    }

    @Test
    fun `a series removed from the database drops its tombstones`() = runTest {
        type.seed(driver, local = true)
        type.synced(type.listed(1), type.listed(2))
        type.file(root, type.url(2)).delete()
        type.synced(type.listed(1))

        type.deleteOwner(driver)

        type.tombstoneCount(driver) shouldBe 0L
    }

    @Test
    fun `a write failing after the capture rolls the capture and the write back`() = runTest {
        type.seed(driver, local = true)
        type.synced(type.listed(1), type.listed(2))
        val id = type.chapterId(driver, type.url(2))

        runCatching {
            ChapterTombstoneRepositoryImpl(database).capturing(type.contentType, OWNER, listOf(id), emptyList()) {
                type.deleteRow(database, id)
                error("the write failed after the delete")
            }
        }

        (type.rowCount(driver) to type.tombstoneCount(driver)) shouldBe (2L to 0L)
    }

    @Test
    fun `a series that is not local writes no tombstone`() = runTest {
        type.seed(driver, local = false)
        type.synced(type.listed(1), type.listed(2))

        type.synced(type.listed(1))

        type.tombstoneCount(driver) shouldBe 0L
    }

    @Test
    fun `a thousand removals are kept and given back`() = runTest {
        type.seed(driver, local = true)
        val all = (1..1000).map { type.listed(it) }
        type.synced(*all.toTypedArray())
        all.drop(1).forEach { type.setState(driver, it.url) }
        all.drop(1).forEach { type.file(root, it.url).delete() }

        val removal = measureTime { type.synced(all.first()) }
        all.drop(1).forEach { type.file(root, it.url).writeText("back") }
        val revival = measureTime { type.synced(*all.toTypedArray()) }
        println("$type: 999 removals captured in $removal, revived in $revival")

        (type.stateOf(driver, type.url(1000)) to type.tombstoneCount(driver)) shouldBe (type.marked to 0L)
    }

    data class Listed(val url: String, val number: Double)

    data class Synced(val foundRelease: Boolean, val newUrls: List<String>)

    /** The user state hanging off one chapter: its row, its history row and its sessions. */
    data class State(
        val read: Boolean,
        val bookmark: Boolean,
        val progress: Long,
        val length: Long,
        val listened: Long,
        val historyAt: Long?,
        val historyTime: Long?,
        val sessions: List<Pair<Long, Long>>,
    )

    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA) {
            override val marked = State(true, true, 7, 20, 0, READ_AT, 60_000, SESSIONS)

            override fun url(i: Int) = "Series/c$i.cbz"

            override fun urlInOtherCase(i: Int) = "Series/C$i.CBZ"

            override fun file(root: File, url: String) = File(root, "local/$url").also { it.parentFile!!.mkdirs() }

            override fun container(root: File): Pair<List<Listed>, List<Listed>> {
                File(root, "local/Series/Vol1").mkdirs()
                file(root, "Series/Vol1/c1.cbz").writeText("c1")
                val chapter = Listed("Series/Vol1/c1.cbz", 1.0)
                return listOf(Listed("Series/Vol1/", 0.99), chapter) to listOf(chapter)
            }

            override suspend fun seed(driver: JdbcSqliteDriver, local: Boolean) {
                driver.exec(
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                        "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                        "($OWNER, ${if (local) LocalSource.ID else 1L}, 'Series', 'T', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                )
            }

            override suspend fun sync(
                database: Database,
                tombstones: LocalChapterTombstones,
                vararg listed: Listed,
            ): Synced {
                val sourceId = MangaRepositoryImpl(database).getMangaById(OWNER).source
                val found = slot<Boolean>()
                val updateManga = mockk<UpdateManga> {
                    coEvery {
                        awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any(), any(), capture(found))
                    } returns true
                }
                val new = SyncChaptersWithSource(
                    downloadManager = mockk(relaxed = true),
                    downloadProvider = mockk(relaxed = true),
                    chapterRepository = ChapterRepositoryImpl(database),
                    shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                    updateManga = updateManga,
                    getExcludedScanlators = mockk<GetExcludedScanlators> {
                        coEvery { await(OWNER) } returns emptySet()
                    },
                    libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                    chapterCopyMarks = InMemoryChapterCopyMarks(),
                    localTombstones = tombstones,
                ).await(
                    listed.map {
                        SChapter.create().apply {
                            url = it.url
                            name = "Chapter ${it.number}"
                            chapter_number = it.number.toFloat()
                        }
                    },
                    Manga.create().copy(id = OWNER, title = "Series", source = sourceId, url = "Series"),
                    mockk<Source> { every { id } returns sourceId },
                )
                return Synced(found.captured, new.map { it.url })
            }

            override suspend fun setState(driver: JdbcSqliteDriver, url: String) {
                driver.exec(
                    "UPDATE chapter SET user_read = 1, user_bookmark = 1, user_last_page_read = 7, page_count = 20 " +
                        "WHERE remote_url = '$url'",
                )
                driver.exec(
                    "INSERT INTO history(manga_id, chapter_id, read_at, read_duration) " +
                        "SELECT manga_id, id, $READ_AT, 60000 FROM chapter WHERE remote_url = '$url'",
                )
                SESSIONS.forEach { (endedAt, duration) ->
                    driver.exec(
                        "INSERT INTO reading_session(chapter_id, ended_at, duration) " +
                            "SELECT id, $endedAt, $duration FROM chapter WHERE remote_url = '$url'",
                    )
                }
            }

            override suspend fun stateOf(driver: JdbcSqliteDriver, url: String): State {
                val row = driver.rows(
                    "SELECT C.user_read, C.user_bookmark, C.user_last_page_read, C.page_count, " +
                        "H.read_at, H.read_duration " +
                        "FROM chapter C LEFT JOIN history H ON H.chapter_id = C.id WHERE C.remote_url = '$url'",
                ) { c -> (0..5).map { c.getLong(it) } }.single()
                val sessions = driver.rows(
                    "SELECT S.ended_at, S.duration FROM reading_session S " +
                        "INNER JOIN chapter C ON C.id = S.chapter_id WHERE C.remote_url = '$url' ORDER BY S.ended_at",
                ) { it.getLong(0)!! to it.getLong(1)!! }
                return State(row[0] == 1L, row[1] == 1L, row[2]!!, row[3]!!, 0, row[4], row[5], sessions)
            }

            override suspend fun chapterId(driver: JdbcSqliteDriver, url: String) =
                driver.long("SELECT id FROM chapter WHERE remote_url = '$url'")

            override suspend fun deleteRow(database: Database, id: Long) {
                database.chapterQueries.removeChaptersWithIds(listOf(id))
            }

            override suspend fun rowCount(driver: JdbcSqliteDriver) = driver.long("SELECT count(*) FROM chapter")

            override suspend fun tombstoneCount(driver: JdbcSqliteDriver) =
                driver.long("SELECT count(*) FROM manga_chapter_tombstone")

            override suspend fun deleteOwner(driver: JdbcSqliteDriver) =
                driver.exec("DELETE FROM manga WHERE id = $OWNER")
        },
        NOVEL(ContentType.NOVELS) {
            override val marked = State(true, true, 4200, 1234, 5000, READ_AT, 60_000, SESSIONS)

            override fun url(i: Int) = LocalNovelSource.chapterPath("Series", "Vol$i.epub", EpubChapterKey("c$i.xhtml"))

            override fun urlInOtherCase(i: Int) =
                LocalNovelSource.chapterPath("Series", "VOL$i.EPUB", EpubChapterKey("c$i.xhtml"))

            override fun file(root: File, url: String): File {
                val (series, volume) = LocalNovelSource.parseChapterPath(url)!!
                return File(root, "localnovels/$series/$volume").also { it.parentFile!!.mkdirs() }
            }

            override fun container(root: File): Pair<List<Listed>, List<Listed>> {
                val inBook = { i: Int ->
                    LocalNovelSource.chapterPath("Series", "Vol1.epub", EpubChapterKey("c$i.xhtml"))
                }
                file(root, inBook(1)).writeText("book")
                val first = Listed(inBook(1), 1.0)
                return listOf(first, Listed(inBook(2), 2.0)) to listOf(first)
            }

            override suspend fun seed(driver: JdbcSqliteDriver, local: Boolean) {
                driver.exec(
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($OWNER, '${if (local) LocalNovelSource.ID else "src"}', 'Series', 'T', 0, 0, 0, 0)",
                )
            }

            override suspend fun sync(
                database: Database,
                tombstones: LocalChapterTombstones,
                vararg listed: Listed,
            ): Synced {
                val result = syncChaptersWithNovelSource(
                    listed.map { ChapterItem(name = "Chapter ${it.number}", path = it.url, chapterNumber = it.number) },
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    LibraryPreferences(InMemoryPreferenceStore()),
                    NovelChapterSyncRules(
                        ChapterNumberOverrideRepositoryImpl(database),
                        InMemoryChapterCopyMarks(),
                        tombstones,
                    ),
                )
                return Synced(result.foundRelease, result.newChapters.map { it.url })
            }

            override suspend fun setState(driver: JdbcSqliteDriver, url: String) {
                driver.exec(
                    "UPDATE novel_chapters SET read = 1, bookmark = 1, last_text_progress = 4200 WHERE url = '$url'",
                )
                driver.exec(
                    "INSERT INTO novel_history(chapter_id, last_read, time_read) " +
                        "SELECT _id, $READ_AT, 60000 FROM novel_chapters WHERE url = '$url'",
                )
                driver.exec(
                    "INSERT INTO novel_chapter_words(chapter_id, words, listened_ms) " +
                        "SELECT _id, 1234, 5000 FROM novel_chapters WHERE url = '$url'",
                )
                SESSIONS.forEach { (endedAt, duration) ->
                    driver.exec(
                        "INSERT INTO novel_reading_session(chapter_id, ended_at, duration) " +
                            "SELECT _id, $endedAt, $duration FROM novel_chapters WHERE url = '$url'",
                    )
                }
            }

            override suspend fun stateOf(driver: JdbcSqliteDriver, url: String): State {
                val row = driver.rows(
                    "SELECT C.read, C.bookmark, C.last_text_progress, coalesce(W.words, 0), " +
                        "coalesce(W.listened_ms, 0), H.last_read, H.time_read FROM novel_chapters C " +
                        "LEFT JOIN novel_history H ON H.chapter_id = C._id " +
                        "LEFT JOIN novel_chapter_words W ON W.chapter_id = C._id WHERE C.url = '$url'",
                ) { c -> (0..6).map { c.getLong(it) } }.single()
                val sessions = driver.rows(
                    "SELECT S.ended_at, S.duration FROM novel_reading_session S " +
                        "INNER JOIN novel_chapters C ON C._id = S.chapter_id WHERE C.url = '$url' ORDER BY S.ended_at",
                ) { it.getLong(0)!! to it.getLong(1)!! }
                return State(row[0] == 1L, row[1] == 1L, row[2]!!, row[3]!!, row[4]!!, row[5], row[6], sessions)
            }

            override suspend fun chapterId(driver: JdbcSqliteDriver, url: String) =
                driver.long("SELECT _id FROM novel_chapters WHERE url = '$url'")

            override suspend fun deleteRow(database: Database, id: Long) {
                database.novel_chaptersQueries.delete(id)
            }

            override suspend fun rowCount(driver: JdbcSqliteDriver) = driver.long("SELECT count(*) FROM novel_chapters")

            override suspend fun tombstoneCount(driver: JdbcSqliteDriver) =
                driver.long("SELECT count(*) FROM novel_chapter_tombstone")

            override suspend fun deleteOwner(driver: JdbcSqliteDriver) =
                driver.exec("DELETE FROM novels WHERE _id = $OWNER")
        },
        ;

        /** [State] as [setState] leaves a chapter. */
        abstract val marked: State

        abstract fun url(i: Int): String

        /** [url] spelled as a case-insensitive file system may hand it back. */
        abstract fun urlInOtherCase(i: Int): String

        /** The file the chapter at [url] is read from, its folders made. */
        abstract fun file(root: File, url: String): File

        /** A listing with a chapter inside a container, then the same listing without it, the container still read. */
        abstract fun container(root: File): Pair<List<Listed>, List<Listed>>

        abstract suspend fun seed(driver: JdbcSqliteDriver, local: Boolean)

        abstract suspend fun sync(database: Database, tombstones: LocalChapterTombstones, vararg listed: Listed): Synced

        abstract suspend fun setState(driver: JdbcSqliteDriver, url: String)

        abstract suspend fun stateOf(driver: JdbcSqliteDriver, url: String): State

        abstract suspend fun chapterId(driver: JdbcSqliteDriver, url: String): Long

        abstract suspend fun deleteRow(database: Database, id: Long)

        abstract suspend fun rowCount(driver: JdbcSqliteDriver): Long

        abstract suspend fun tombstoneCount(driver: JdbcSqliteDriver): Long

        abstract suspend fun deleteOwner(driver: JdbcSqliteDriver)

        fun listed(i: Int) = Listed(url(i), i.toDouble())
    }

    /** Syncs [listed], whose files are written first so the sync finds them where it checks. */
    private suspend fun Type.synced(vararg listed: Listed): Synced {
        listed.forEach { file(root, it.url).takeUnless(File::exists)?.writeText("pages") }
        return sync(database, tombstones, *listed)
    }

    companion object {
        private const val OWNER = 1L
        private const val READ_AT = 1_700_000_000_000L
        private val SESSIONS = listOf(1_700_000_000_000L to 30_000L, 1_700_000_100_000L to 30_000L)
        private val UNREAD = State(false, false, 0, 0, 0, null, null, emptyList())

        @JvmStatic
        @Parameters(name = "{0}")
        fun types() = Type.entries.map { arrayOf(it) }

        private suspend fun JdbcSqliteDriver.exec(sql: String) {
            execute(null, sql, 0).await()
        }

        private suspend fun <T> JdbcSqliteDriver.rows(sql: String, map: (SqlCursor) -> T): List<T> = executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(map(cursor)) }) },
            0,
        ).await()

        private suspend fun JdbcSqliteDriver.long(sql: String): Long = rows(sql) { it.getLong(0)!! }.single()
    }
}
