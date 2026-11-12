package reikai.data.chapter

import androidx.sqlite.driver.AndroidSQLiteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The capture, the sync's own write and the revive are one transaction on the driver production opens
 * the database with, over Robolectric's SQLite: the sync's `transactionWithResult` joins the enclosing
 * one the repository opens, so a failure after the capture rolls the capture and the write back
 * together, and a success commits them together. The JVM driver's answer is
 * ChapterTombstoneConformanceTest's; this pins the Android driver's.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ChapterTombstoneTransactionTest(private val type: ContentType) {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var driver: SqlDriver
    private lateinit var database: Database

    @Before
    fun setUp() {
        driver = AndroidxSqliteDriver(
            driver = AndroidSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.File(tmp.newFile("tachiyomi.db").absolutePath),
            schema = Database.Schema,
            configuration = AndroidxSqliteConfiguration(
                isForeignKeyConstraintsEnabled = true,
                concurrencyModel = MultipleReadersSingleWriter(isWal = true, walCount = 4),
            ),
        )
        database = DatabaseBindings.providesDatabase(driver)
    }

    @After
    fun tearDown() = driver.close()

    @Test
    fun `a write failing after the capture rolls the capture and the write back`() = runTest {
        val id = seedChapter()

        runCatching {
            ChapterTombstoneRepositoryImpl(database).capturing(type, OWNER, listOf(id), emptyList()) {
                deleteRow(id)
                error("the write failed after the delete")
            }
        }

        (count(chapterTable) to count(tombstoneTable)) shouldBe (1L to 0L)
    }

    @Test
    fun `a write that succeeds commits the capture with it`() = runTest {
        val id = seedChapter()

        ChapterTombstoneRepositoryImpl(database).capturing(type, OWNER, listOf(id), emptyList()) { deleteRow(id) }

        (count(chapterTable) to count(tombstoneTable)) shouldBe (0L to 1L)
    }

    private val chapterTable get() = if (type == ContentType.NOVELS) "novel_chapters" else "chapter"
    private val tombstoneTable get() = if (type ==
        ContentType.NOVELS
    ) {
        "novel_chapter_tombstone"
    } else {
        "manga_chapter_tombstone"
    }

    /**
     * The owner and one chapter, written as rows: the manga sync's own insert runs RETURNING, which the
     * framework's statement API under Robolectric refuses, and the write under test is the delete, nested.
     */
    private suspend fun seedChapter(): Long {
        if (type == ContentType.NOVELS) {
            exec(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($OWNER, 'local', 'Series', 'T', 0, 0, 0, 0)",
            )
            exec(
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, source_order, " +
                    "date_fetch) VALUES ($CHAPTER, $OWNER, 'Series/c1', 'c1', 1, 0, 1.0, 0, 0)",
            )
        } else {
            exec(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($OWNER, 0, 'Series', 'T', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
            )
            exec(
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_chapter_number, remote_order, " +
                    "remote_memo, user_read, user_bookmark, user_last_page_read, state_date_fetch) VALUES " +
                    "($CHAPTER, $OWNER, 'Series/c1', 'c1', 1.0, 0, '{}', 1, 0, 0, 0)",
            )
        }
        return CHAPTER
    }

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    /** The delete inside a transaction of its own, as the sync's `updateFromRemote` writes it, so it nests. */
    private suspend fun deleteRow(id: Long) = database.transactionWithResult {
        if (type == ContentType.NOVELS) {
            database.novel_chaptersQueries.delete(id)
        } else {
            database.chapterQueries.removeChaptersWithIds(listOf(id))
        }
    }

    private suspend fun count(table: String): Long = driver.executeQuery(
        null,
        "SELECT count(*) FROM $table",
        { cursor -> QueryResult.Value(cursor.next().value.let { cursor.getLong(0)!! }) },
        0,
    ).await()

    companion object {
        private const val OWNER = 1L
        private const val CHAPTER = 7L

        @JvmStatic
        @Parameters(name = "{0}")
        fun types() = listOf(arrayOf(ContentType.MANGA), arrayOf(ContentType.NOVELS))
    }
}
