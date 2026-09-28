package reikai.data.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.db.SqlDelightTransactions
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * A repository that catches its own failure inside a nested transaction still fails the enclosing
 * one, which SQLDelight then rolls back without throwing. A restore batch has to see that, or every
 * entry in it is lost with nothing reported. Runs the real transaction over an in-memory database.
 */
class RestoreBatchRollbackTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "CREATE TABLE restored(name TEXT NOT NULL)", 0).await()
            database = DatabaseBindings.providesDatabase(driver)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    /** Writes the entry's row; "bad" then fails a nested write and swallows it, as the repositories do. */
    private suspend fun restore(name: String) {
        driver.execute(null, "INSERT INTO restored(name) VALUES (?)", 1) { bindString(0, name) }.await()
        if (name == "bad") {
            try {
                database.transaction { error("write failed") }
            } catch (_: IllegalStateException) {
            }
        }
    }

    private fun restoredNames(): List<String> = driver.executeQuery(
        null,
        "SELECT name FROM restored ORDER BY rowid",
        { cursor ->
            QueryResult.Value(
                buildList { while (cursor.next().value) add(cursor.getString(0)!!) },
            )
        },
        0,
    ).value

    @Test
    fun `a quietly rolled-back batch keeps its good entries and reports the bad one`() = runTest {
        val failures = restoreBatch(listOf("a", "bad", "c"), SqlDelightTransactions(database), ::restore)

        listOf(restoredNames(), failures.map { it.first }) shouldBe listOf(listOf("a", "c"), listOf("bad"))
    }
}
