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
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import java.io.File

/**
 * 85.sqm over a novel group and a manga group, both stitched, reached from the committed 43.db snapshot
 * through the real migrations (database.md). Only the novel stitch rules changed.
 */
class NovelRestitchMigrationTest {

    private lateinit var file: File
    private lateinit var driver: JdbcSqliteDriver

    @BeforeEach
    fun setUp() = runTest {
        file = File.createTempFile("novel-restitch", ".db")
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
    fun `every novel group restitches once and a manga group keeps its stitch`() = runTest {
        val foreignKeyFailures = migrate()

        longs("SELECT group_id FROM merged_group_ranking") shouldBe listOf(2L)
        longs("SELECT group_id FROM merged_group_pending WHERE claimed = 0") shouldBe listOf(1L)
        foreignKeyFailures.shouldBeEmpty()
    }

    /** One transaction, as the production driver migrates. Returns what the foreign-key check reports. */
    private suspend fun migrate(): List<Long> = DatabaseBindings.providesDatabase(driver).transactionWithResult {
        Database.Schema.migrate(driver, BEFORE, Database.Schema.version).await()
        longs("SELECT rowid FROM pragma_foreign_key_check")
    }

    private suspend fun longs(sql: String): List<Long> = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getLong(0)!!) }) },
        0,
    ).await()

    companion object {
        /** The schema version 85.sqm migrates from. */
        private const val BEFORE = 85L

        private val SEED = listOf(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                "VALUES (1, 's', '/n/1', 'n1', 0, 1, 0, 1000), (2, 't', '/n/2', 'n1', 0, 1, 0, 1000)",
            "INSERT INTO merge_group(_id, content_type) VALUES (1, 1), (2, 0)",
            "INSERT INTO merge_group_novel(group_id, novel_id) VALUES (1, 1), (1, 2)",
            "INSERT INTO merged_group_ranking(group_id, ranking) VALUES (1, 'r'), (2, 'r')",
            // The membership triggers list the group as it is seeded; a reconcile would have cleared that.
            "DELETE FROM merged_group_pending WHERE claimed = 0",
        )
    }
}
