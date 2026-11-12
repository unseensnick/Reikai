package reikai.data.migration

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import java.io.File

/**
 * 79.sqm over kept states written in the shape the schema had just before it, reached from the committed 43.db
 * snapshot through the real migrations (database.md).
 */
class TombstoneNumberMigrationTest {

    private lateinit var file: File
    private lateinit var driver: JdbcSqliteDriver

    @BeforeEach
    fun setUp() = runTest {
        file = File.createTempFile("tombstone-number", ".db")
        File("src/main/sqldelight/43.db").copyTo(file, overwrite = true)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        DatabaseBindings.providesDatabase(driver).transaction {
            Database.Schema.migrate(driver, 43, BEFORE).await()
        }
        SEED.forEach { driver.execute(null, it, 0).await() }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
        file.delete()
    }

    @Test
    fun `a manga kept state keeps every other field`() = runTest {
        migrate()

        strings(
            "SELECT manga_id || ',' || url || ',' || read || ',' || bookmark || ',' || last_page_read || ',' || " +
                "page_count || ',' || date_fetch || ',' || history_read_at || ',' || history_read_duration || ',' || " +
                "removed_at FROM manga_chapter_tombstone",
        ) shouldBe listOf("1,/c/1,1,1,3,20,500,200,90,7")
    }

    @Test
    fun `a novel kept state keeps every other field`() = runTest {
        migrate()

        strings(
            "SELECT novel_id || ',' || url || ',' || read || ',' || bookmark || ',' || last_text_progress || ',' || " +
                "words || ',' || listened_ms || ',' || date_fetch || ',' || history_last_read || ',' || " +
                "history_time_read || ',' || removed_at FROM novel_chapter_tombstone",
        ) shouldBe listOf("1,/n/c/1,1,0,4,800,60,500,200,90,7")
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga_chapter_tombstone", "novel_chapter_tombstone"])
    fun `the chapter number is gone`(table: String) = runTest {
        migrate()

        strings("SELECT name FROM pragma_table_info('$table') WHERE name = 'chapter_number'").shouldBeEmpty()
    }

    @Test
    fun `the kept sessions stay`() = runTest {
        migrate()

        strings(
            "SELECT url FROM manga_chapter_tombstone_session UNION ALL SELECT url FROM novel_chapter_tombstone_session",
        ) shouldBe listOf("/c/1", "/n/c/1")
    }

    @Test
    fun `the migration leaves every foreign key satisfied`() = runTest {
        migrate().shouldBeEmpty()
    }

    /** One transaction, as the production driver migrates. Returns what the foreign-key check reports. */
    private suspend fun migrate(): List<String?> = DatabaseBindings.providesDatabase(driver).transactionWithResult {
        Database.Schema.migrate(driver, BEFORE, Database.Schema.version).await()
        strings("SELECT rowid FROM pragma_foreign_key_check")
    }

    private suspend fun strings(sql: String): List<String?> = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)) }) },
        0,
    ).await()

    companion object {
        /** The schema version 79.sqm migrates from. */
        private const val BEFORE = 79L

        private val SEED = listOf(
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                "(1, 0, 'Series', 'T', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                "VALUES (1, 'local', 'Saga', 'n1', 0, 1, 0, 1000)",
            "INSERT INTO manga_chapter_tombstone(manga_id, url, chapter_number, read, bookmark, last_page_read, " +
                "page_count, date_fetch, history_read_at, history_read_duration, removed_at) " +
                "VALUES (1, '/c/1', 5.0, 1, 1, 3, 20, 500, 200, 90, 7)",
            "INSERT INTO novel_chapter_tombstone(novel_id, url, chapter_number, read, bookmark, last_text_progress, " +
                "words, listened_ms, date_fetch, history_last_read, history_time_read, removed_at) " +
                "VALUES (1, '/n/c/1', 5.0, 1, 0, 4, 800, 60, 500, 200, 90, 7)",
            "INSERT INTO manga_chapter_tombstone_session(manga_id, url, ended_at, duration) VALUES (1, '/c/1', 9, 10)",
            "INSERT INTO novel_chapter_tombstone_session(novel_id, url, ended_at, duration) VALUES (1, '/n/c/1', 9, 10)",
        )
    }
}
