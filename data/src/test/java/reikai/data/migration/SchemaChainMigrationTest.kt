package reikai.data.migration

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import java.io.File

/**
 * The real migrations run over rows written into the committed 43.db snapshot, the schema before
 * 43.sqm. The snapshot never changes, so these rows stay valid however many migrations follow, which a
 * hand-built older schema would not (database.md). The driver migrates with foreign keys off and then
 * rejects any row a migration left pointing nowhere, so each case migrates the same way.
 */
class SchemaChainMigrationTest {

    private lateinit var file: File
    private lateinit var driver: JdbcSqliteDriver

    @BeforeEach
    fun setUp() {
        file = File.createTempFile("schema-chain", ".db")
        File("src/main/sqldelight/43.db").copyTo(file, overwrite = true)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    }

    @AfterEach
    fun tearDown() {
        driver.close()
        file.delete()
    }

    @Test
    fun `copies of a manga merge into the one in the library`() = runTest {
        manga(id = 1, url = "/g/1", favorite = false)
        manga(id = 2, url = "/g/1", favorite = true)
        manga(id = 3, url = "/g/1", favorite = false)

        migrate()

        longs("SELECT id FROM manga ORDER BY id") shouldBe listOf(2L)
    }

    @Test
    fun `a chapter read on a merged copy stays read`() = runTest {
        manga(id = 1, url = "/g/1", favorite = false)
        manga(id = 2, url = "/g/1", favorite = true)
        chapter(id = 10, mangaId = 1, url = "/c/1", read = true)
        chapter(id = 20, mangaId = 2, url = "/c/1", read = false)

        migrate()

        longs("SELECT user_read FROM chapter WHERE manga_id = 2") shouldBe listOf(1L)
    }

    @Test
    fun `history on a merged copy follows its chapter to the kept entry`() = runTest {
        manga(id = 1, url = "/g/1", favorite = false)
        manga(id = 2, url = "/g/1", favorite = true)
        chapter(id = 10, mangaId = 1, url = "/c/1", read = true)
        exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (10, 500, 60)")

        migrate()

        longs("SELECT manga_id FROM history") shouldBe listOf(2L)
    }

    @Test
    fun `merging leaves no row pointing at a removed entry`() = runTest {
        manga(id = 1, url = "/g/1", favorite = false)
        manga(id = 2, url = "/g/1", favorite = true)
        chapter(id = 10, mangaId = 1, url = "/c/1", read = true)
        exec("INSERT INTO categories(_id, name, sort, flags) VALUES (5, 'A', 1, 0)")
        exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 5)")
        exec(
            "INSERT INTO manga_sync(manga_id, sync_id, remote_id, title, last_chapter_read, total_chapters, status, " +
                "score, remote_url, start_date, finish_date) VALUES (1, 1, 7, 't', 1, 0, 0, 0, 'u', 0, 0)",
        )

        migrate().shouldBeEmpty()
    }

    @Test
    fun `a merge group the dedupe did not touch keeps its stitch`() = runTest {
        manga(id = 1, url = "/a", favorite = true)
        manga(id = 2, url = "/b", favorite = true)
        chapter(id = 10, mangaId = 1, url = "/c/1", read = false)
        exec("INSERT INTO merge_group(_id, content_type) VALUES (1, 0)")
        exec("INSERT INTO merge_group_manga(group_id, manga_id) VALUES (1, 1), (1, 2)")
        exec(
            "INSERT INTO merged_chapter_unit(chapter_id, group_id, unit, copy_order, derived_number) VALUES (10, 1, 0, 0, 1)",
        )

        migrate()

        longs("SELECT chapter_id FROM merged_chapter_unit") shouldBe listOf(10L)
    }

    @Test
    fun `the system category still refuses deletion`() = runTest {
        migrate()

        shouldThrowAny { exec("DELETE FROM category WHERE id = 0") }
    }

    /**
     * One transaction, as the production driver migrates, since a migration's temp tables live only as
     * long as its connection. Foreign keys are off, sqlite-jdbc's default. Returns the rows the
     * foreign-key check reports, which the production driver throws on.
     */
    private suspend fun migrate(): List<Long> = DatabaseBindings.providesDatabase(driver).transactionWithResult {
        Database.Schema.migrate(driver, 43, Database.Schema.version).await()
        longs("SELECT rowid FROM pragma_foreign_key_check")
    }

    private suspend fun manga(id: Long, url: String, favorite: Boolean) = exec(
        "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, " +
            "cover_last_modified, date_added) VALUES ($id, 1, '$url', 'm$id', 0, ${if (favorite) 1 else 0}, 0, 0, 0, " +
            "0, ${if (favorite) 1000 else 0})",
    )

    private suspend fun chapter(id: Long, mangaId: Long, url: String, read: Boolean) = exec(
        "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, " +
            "source_order, date_fetch, date_upload) VALUES ($id, $mangaId, '$url', 'c', ${if (read) 1 else 0}, 0, 0, " +
            "1, 0, 0, 0)",
    )

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private suspend fun longs(sql: String): List<Long> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(cursor.getLong(0)!!)
                },
            )
        },
        0,
    ).await()
}
