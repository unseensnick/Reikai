package reikai.data.readinglog

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.history.HistoryRepositoryImpl
import kotlin.time.Instant

/** The reading log's rules, run over both content types since each has its own table and clear path. */
class ReadingLogRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var log: ReadingLogRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            database = DatabaseBindings.providesDatabase(driver)
            log = ReadingLogRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a session is recorded`(type: ContentType) = runTest {
        log.record(type, chapterId = 1L, endedAt = Instant.fromEpochMilliseconds(1_000L), duration = 60_000L)

        sessionCount(type) shouldBe 1L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a session that spent no time is dropped`(type: ContentType) = runTest {
        log.record(type, chapterId = 1L, endedAt = Instant.fromEpochMilliseconds(1_000L), duration = 0L)

        sessionCount(type) shouldBe 0L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the same session recorded twice is stored once`(type: ContentType) = runTest {
        repeat(2) {
            log.record(type, chapterId = 1L, endedAt = Instant.fromEpochMilliseconds(1_000L), duration = 60_000L)
        }

        sessionCount(type) shouldBe 1L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `clearing all history clears that type's log and leaves the other's`(type: ContentType) = runTest {
        val other = if (type == ContentType.MANGA) ContentType.NOVELS else ContentType.MANGA
        listOf(type, other).forEach { log.record(it, 1L, Instant.fromEpochMilliseconds(1_000L), 60_000L) }

        when (type) {
            ContentType.MANGA -> HistoryRepositoryImpl(database).deleteAllHistory()
            else -> NovelHistoryRepositoryImpl(database).deleteAllNovelHistory()
        }

        (sessionCount(type) to sessionCount(other)) shouldBe (0L to 1L)
    }

    @Test
    fun `removing reset manga history removes only those chapters' sessions`() = runTest {
        exec("INSERT INTO history(manga_id, chapter_id, read_at, read_duration) VALUES (1, 1, NULL, 60000)")
        exec("INSERT INTO history(manga_id, chapter_id, read_at, read_duration) VALUES (1, 2, 5000, 60000)")
        log.record(ContentType.MANGA, 1L, Instant.fromEpochMilliseconds(1_000L), 60_000L)
        log.record(ContentType.MANGA, 2L, Instant.fromEpochMilliseconds(2_000L), 60_000L)

        HistoryRepositoryImpl(database).deleteResetHistory()

        sessionCount(ContentType.MANGA) shouldBe 1L
    }

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private suspend fun sessionCount(type: ContentType): Long {
        val table = if (type == ContentType.MANGA) "reading_session" else "novel_reading_session"
        return driver.executeQuery(
            null,
            "SELECT count(*) FROM $table",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else 0L)
            },
            0,
        ).await()
    }
}
