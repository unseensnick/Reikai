package reikai.data.recents

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.domain.novel.model.NovelHistoryUpdate
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.domain.history.model.HistoryUpdate
import java.util.Date

/**
 * The History tab's two feeds answer alike over the real SQL: one row per entry at its latest read, a
 * reset hides an entry until it is read again, and clearing deletes rather than resets. The manga half
 * is Mihon's, the novel half Reikai's.
 */
class HistoryFeedConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the feed shows each entry once, at its latest-read chapter`(type: Type) = runTest {
        type.seed(driver)

        type.feed(database, query = "") shouldBe listOf(1L to 2L, 2L to 3L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `searching keeps only the entries whose title contains the query`(type: Type) = runTest {
        type.seed(driver)

        type.feed(database, query = "lph") shouldBe listOf(1L to 2L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `resetting an entry's history drops it from the feed`(type: Type) = runTest {
        type.seed(driver)

        type.resetEntry(database, 1L)

        type.feed(database, query = "") shouldBe listOf(2L to 3L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `reading a reset entry again brings it back`(type: Type) = runTest {
        type.seed(driver)
        type.resetEntry(database, 1L)

        type.read(database, chapterId = 1L, at = 600L, duration = 0L)

        type.feed(database, query = "") shouldBe listOf(1L to 1L, 2L to 3L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `reading a chapter again adds the session's time to its total`(type: Type) = runTest {
        type.seed(driver)

        type.read(database, chapterId = 3L, at = 700L, duration = 25L)

        type.readDuration(database, entryId = 2L) shouldBe 35L
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `resuming picks the newest read`(type: Type) = runTest {
        type.seed(driver)

        type.lastReadChapter(database) shouldBe 2L
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `resuming finds nothing once every entry's history is reset`(type: Type) = runTest {
        type.seed(driver)
        type.resetEntry(database, 1L)
        type.resetEntry(database, 2L)

        type.lastReadChapter(database) shouldBe null
    }

    /** A reset keeps the reading time, so a total of zero shows the rows were deleted. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `clearing all history leaves no reading time behind`(type: Type) = runTest {
        type.seed(driver)

        type.clearAll(database)

        type.totalReadDuration(database) shouldBe 0L
    }

    /**
     * Entry 1 "Alpha" with chapters 1 and 2 read at 300 and 500, entry 2 "Beta" with chapter 3 read at
     * 400, every chapter with 10 of reading time.
     */
    enum class Type(private val statements: List<String>) {
        MANGA(
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES (1, 1, 'a', 'Alpha', " +
                    "0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), (2, 1, 'b', 'Beta', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES " +
                    "(1, 1, 'c1', 'n', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}'), " +
                    "(2, 1, 'c2', 'n', NULL, 0, 0, 0, 2.0, 0, 0, 0, '{}'), " +
                    "(3, 2, 'c3', 'n', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}')",
                "INSERT INTO history(id, chapter_id, read_at, read_duration, manga_id) VALUES " +
                    "(1, 1, 300, 10, 1), (2, 2, 500, 10, 1), (3, 3, 400, 10, 2)",
            ),
        ) {
            override suspend fun feed(database: Database, query: String) =
                HistoryRepositoryImpl(database).getHistory(query, emptyList(), emptyList()).first()
                    .map { it.mangaId to it.chapterId }

            override suspend fun resetEntry(database: Database, entryId: Long) =
                HistoryRepositoryImpl(database).resetHistoryByMangaId(entryId)

            override suspend fun read(database: Database, chapterId: Long, at: Long, duration: Long) =
                HistoryRepositoryImpl(database).upsertHistory(HistoryUpdate(chapterId, Date(at), duration))

            override suspend fun readDuration(database: Database, entryId: Long) =
                HistoryRepositoryImpl(database).getHistoryByMangaId(entryId).single().readDuration

            override suspend fun lastReadChapter(database: Database) =
                HistoryRepositoryImpl(database).getLastHistory()?.chapterId

            override suspend fun clearAll(database: Database) {
                HistoryRepositoryImpl(database).deleteAllHistory()
            }

            override suspend fun totalReadDuration(database: Database) =
                HistoryRepositoryImpl(database).getTotalReadDuration()
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES (1, 'src', 'a', 'Alpha', 0, 0, 0, 0), (2, 'src', 'b', 'Beta', 0, 0, 0, 0)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 1, 'c1', 'n', 0, 0, 0, 1.0, 0, 0, 0), (2, 1, 'c2', 'n', 0, 0, 0, 2.0, 0, 0, 0), " +
                    "(3, 2, 'c3', 'n', 0, 0, 0, 1.0, 0, 0, 0)",
                "INSERT INTO novel_history(_id, chapter_id, last_read, time_read) VALUES " +
                    "(1, 1, 300, 10), (2, 2, 500, 10), (3, 3, 400, 10)",
            ),
        ) {
            override suspend fun feed(database: Database, query: String) =
                NovelHistoryRepositoryImpl(database).getNovelHistory(query).first().map { it.novelId to it.chapterId }

            override suspend fun resetEntry(database: Database, entryId: Long) =
                NovelHistoryRepositoryImpl(database).resetNovelHistoryByNovelId(entryId)

            override suspend fun read(database: Database, chapterId: Long, at: Long, duration: Long) =
                NovelHistoryRepositoryImpl(database).upsertNovelHistory(NovelHistoryUpdate(chapterId, at, duration))

            override suspend fun readDuration(database: Database, entryId: Long) =
                NovelHistoryRepositoryImpl(database).getHistoryByNovelId(entryId).single().readDuration

            override suspend fun lastReadChapter(database: Database) =
                NovelHistoryRepositoryImpl(database).getLastNovelHistory()?.chapterId

            override suspend fun clearAll(database: Database) {
                NovelHistoryRepositoryImpl(database).deleteAllNovelHistory()
            }

            override suspend fun totalReadDuration(database: Database) =
                NovelHistoryRepositoryImpl(database).getTotalReadDuration()
        },
        ;

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        /** (entry id, chapter id) per feed row, in feed order. */
        abstract suspend fun feed(database: Database, query: String): List<Pair<Long, Long>>

        abstract suspend fun resetEntry(database: Database, entryId: Long)

        abstract suspend fun read(database: Database, chapterId: Long, at: Long, duration: Long)

        /** The reading time of [entryId]'s only chapter. */
        abstract suspend fun readDuration(database: Database, entryId: Long): Long

        abstract suspend fun lastReadChapter(database: Database): Long?

        abstract suspend fun clearAll(database: Database)

        abstract suspend fun totalReadDuration(database: Database): Long
    }
}
