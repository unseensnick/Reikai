package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import reikai.storage.FakeSafProvider
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import kotlin.time.Instant

/**
 * A local series whose folder was renamed outside the app: the folders holding its chapter files are offered,
 * the one the user picks takes the entry and its read state, and nothing is ever picked for them. Both
 * content types on the fake picked-folder provider.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LocalSeriesRelinkTest(private val type: ContentType) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var relink: LocalSeriesRelink
    private var nextMangaId = 1L

    @Before
    fun setUp() = runTest {
        FakeSafProvider.install(context, tmp.root)
        root = tmp.newFolder("Reikai", "local")
        Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        val storage = mockk<StorageManager> {
            every { getLocalSourceDirectory() } answers { picked(root) }
            every { getLocalNovelsDirectory() } answers { picked(root) }
        }
        relink = LocalSeriesRelink(
            context = context,
            mangaRepository = MangaRepositoryImpl(database),
            chapterRepository = ChapterRepositoryImpl(database),
            novelRepository = NovelRepositoryImpl(database),
            novelChapterRepository = NovelChapterRepositoryImpl(database),
            localFiles = LocalSourceFileSystem(storage),
            storageManager = storage,
            chapterMoves = LocalChapterMoveRepositoryImpl(database, ChapterTombstoneRepositoryImpl(database)),
            seriesLocks = LocalSeriesLocks().apply { open() },
        )
    }

    @After
    fun tearDown() = driver.close()

    @Test
    fun `the folder a series was renamed to is offered and, once picked, takes the entry with its read state`() =
        runTest {
            val id = seeded("Saga")
            markRead(chapterUrls("Saga").first())
            File(root, "Saga").renameTo(File(root, "Saga Renamed"))

            val offered = relink.candidates(type, id)
            val result = relink.relink(type, id, "Saga Renamed")

            listOf(
                offered,
                result is LocalMoveResult.Moved,
                urls(id),
                readUrls(id),
                relink.candidates(type, id),
            ) shouldBe
                listOf(
                    listOf("Saga Renamed"),
                    true,
                    chapterUrls("Saga Renamed").sorted(),
                    chapterUrls("Saga Renamed").take(1),
                    null,
                )
        }

    @Test
    fun `a relinked series takes the kept state of a chapter whose file was already gone`() = runTest {
        val id = seeded("Saga")
        // The chapter's file went before the rename: no row, only its kept state at the old address.
        val lost = chapterUrls("Saga").last()
        driver.exec(deleteRow(lost))
        driver.exec(tombstone(id, lost))
        File(root, "Saga").renameTo(File(root, "Saga Renamed"))

        relink.relink(type, id, "Saga Renamed")

        driver.rows(tombstoneUrls(id)) { it.getString(0)!! } shouldBe listOf(chapterUrls("Saga Renamed").last())
    }

    @Test
    fun `two folders holding the files are both offered and neither is taken`() = runTest {
        val id = seeded("Saga")
        File(root, "Saga").renameTo(File(root, "Saga A"))
        File(root, "Saga A").copyRecursively(File(root, "Saga B"))

        val offered = relink.candidates(type, id)

        (offered to urls(id)) shouldBe (listOf("Saga A", "Saga B") to chapterUrls("Saga").sorted())
    }

    @Test
    fun `a folder another entry already holds is not offered`() = runTest {
        val id = seeded("Saga")
        File(root, "Saga").renameTo(File(root, "Saga A"))
        File(root, "Saga A").copyRecursively(File(root, "Saga B"))
        entry("Saga B")

        relink.candidates(type, id) shouldBe listOf("Saga A")
    }

    @Test
    fun `a missing folder nothing stands in for offers nothing and loses no row`() = runTest {
        val id = seeded("Saga")
        File(root, "Saga").deleteRecursively()

        (relink.candidates(type, id) to urls(id)) shouldBe (emptyList<String>() to chapterUrls("Saga").sorted())
    }

    @Test
    fun `a link is refused once the folder is back where the address says`() = runTest {
        val id = seeded("Saga")
        File(root, "Saga").renameTo(File(root, "Saga Renamed"))
        val offered = relink.candidates(type, id)
        // Put back as a copy, so the offered folder still holds the files when the answer arrives.
        File(root, "Saga Renamed").copyRecursively(File(root, "Saga"))

        val result = relink.relink(type, id, "Saga Renamed")

        listOf(offered, result is LocalMoveResult.Failed, urls(id)) shouldBe
            listOf(listOf("Saga Renamed"), true, chapterUrls("Saga").sorted())
    }

    /** Series [folder] on disk and in the database, with a row for each of its chapter files. */
    private suspend fun seeded(folder: String): Long {
        if (type == ContentType.NOVELS) {
            File(root, folder).mkdirs()
            File(root, "$folder/$folder - Vol. 1.epub").writeBytes(byteArrayOf(1))
            File(root, "$folder/$folder - Vol. 2.epub").writeBytes(byteArrayOf(2))
        } else {
            File(root, "$folder/Vol 1/Ch 1").mkdirs()
            File(root, "$folder/Vol 1/Ch 1/001.jpg").writeBytes(byteArrayOf(1))
            File(root, "$folder/Vol 1/Ch 2").mkdirs()
            File(root, "$folder/Vol 1/Ch 2/001.jpg").writeBytes(byteArrayOf(1))
            File(root, "$folder/Extra.cbz").writeBytes(byteArrayOf(3))
        }
        val id = entry(folder)
        val urls = chapterUrls(folder)
        if (type == ContentType.NOVELS) {
            urls.forEach { url ->
                NovelChapterRepositoryImpl(database).insert(
                    NovelChapter(
                        id = -1L, novelId = id, url = url, name = url, read = false, bookmark = false,
                        lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L,
                        dateFetch = Instant.fromEpochMilliseconds(0L), dateUpload = null, page = "",
                    ),
                )
            }
        } else {
            ChapterRepositoryImpl(database).updateFromRemote(
                removedIds = emptyList(),
                added = urls.map { Chapter.create().copy(mangaId = id, url = it, name = it) },
                updated = emptyList(),
            )
        }
        return id
    }

    /** The chapter addresses series [folder] has, as each local source lists them. */
    private fun chapterUrls(folder: String): List<String> = if (type == ContentType.NOVELS) {
        listOf(
            LocalNovelSource.chapterPath(folder, "Saga - Vol. 1.epub", EpubChapterKey("c1.xhtml")),
            LocalNovelSource.chapterPath(folder, "Saga - Vol. 2.epub", EpubChapterKey("c2.xhtml")),
        )
    } else {
        listOf("$folder/Vol 1/Ch 1", "$folder/Vol 1/Ch 2", "$folder/Extra.cbz")
    }

    private suspend fun entry(url: String): Long = if (type == ContentType.NOVELS) {
        NovelRepositoryImpl(
            database,
        ).insert(Novel.create().copy(source = LocalNovelSource.ID, url = url, title = url))!!
    } else {
        val id = nextMangaId++
        driver.exec(
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                "($id, ${LocalSource.ID}, '$url', '$url', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
        )
        id
    }

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

    private suspend fun markRead(url: String) = driver.exec(
        if (type == ContentType.NOVELS) {
            "UPDATE novel_chapters SET read = 1 WHERE url = '$url'"
        } else {
            "UPDATE chapter SET user_read = 1 WHERE remote_url = '$url'"
        },
    )

    private suspend fun urls(owner: Long, readOnly: Boolean = false): List<String> {
        val sql = if (type == ContentType.NOVELS) {
            "SELECT url FROM novel_chapters WHERE novel_id = $owner${if (readOnly) " AND read = 1" else ""} ORDER BY url"
        } else {
            "SELECT remote_url FROM chapter WHERE manga_id = $owner${if (readOnly) " AND user_read = 1" else ""} " +
                "ORDER BY remote_url"
        }
        return driver.rows(sql) { it.getString(0)!! }
    }

    private suspend fun readUrls(owner: Long) = urls(owner, readOnly = true)

    private fun deleteRow(url: String) = if (type == ContentType.NOVELS) {
        "DELETE FROM novel_chapters WHERE url = '$url'"
    } else {
        "DELETE FROM chapter WHERE remote_url = '$url'"
    }

    private fun tombstone(owner: Long, url: String) = if (type == ContentType.NOVELS) {
        "INSERT INTO novel_chapter_tombstone(novel_id, url, chapter_number, read, bookmark, last_text_progress, " +
            "date_fetch, removed_at) VALUES ($owner, '$url', 1.0, 1, 0, 0, 0, 0)"
    } else {
        "INSERT INTO manga_chapter_tombstone(manga_id, url, chapter_number, read, bookmark, last_page_read, " +
            "page_count, date_fetch, removed_at) VALUES ($owner, '$url', 1.0, 1, 0, 0, 0, 0, 0)"
    }

    private fun tombstoneUrls(owner: Long) = if (type == ContentType.NOVELS) {
        "SELECT url FROM novel_chapter_tombstone WHERE novel_id = $owner"
    } else {
        "SELECT url FROM manga_chapter_tombstone WHERE manga_id = $owner"
    }

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

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)
    }
}
