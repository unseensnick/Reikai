package mihon.app.di

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Nineteen `.sq` files declare a foreign key, and every `ON DELETE CASCADE` in the schema assumes the
 * connection enforces them. The unit tests that demonstrate enforcement build a `JdbcSqliteDriver` and
 * turn it on with a hand-written pragma, which is neither the driver nor the mechanism production uses,
 * so they cannot answer for it. This builds the driver exactly as `AppBindings.providesSqlDriver` does
 * and asks the question there, with a control that proves the probe can tell the two apart.
 */
@RunWith(AndroidJUnit4::class)
class ForeignKeyEnforcementTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val opened = mutableListOf<Pair<SqlDriver, String>>()

    @After
    fun tearDown() {
        opened.forEach { (driver, name) ->
            runCatching { driver.close() }
            listOf(name, "$name-wal", "$name-shm").forEach { context.getDatabasePath(it).delete() }
        }
    }

    @Test
    fun theProductionDriverRejectsAnOrphaningInsert() {
        val driver = openDriver("fk_probe_enforced.db", enforced = true)
        try {
            driver.executeBlocking("INSERT INTO child (parent_id) VALUES (404)")
            fail(
                "The production driver accepted a row referencing a parent that does not exist, so every ON DELETE CASCADE in the schema is decorative.",
            )
        } catch (e: Exception) {
            assertTrue(
                "Expected a foreign key rejection, got: ${e.message}",
                e.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true) ||
                    e.message.orEmpty().contains("constraint", ignoreCase = true),
            )
        }
    }

    @Test
    fun deletingTheParentCascadesToTheChild() {
        val driver = openDriver("fk_probe_cascade.db", enforced = true)
        driver.executeBlocking("INSERT INTO parent (_id) VALUES (7)")
        driver.executeBlocking("INSERT INTO child (parent_id) VALUES (7)")
        driver.executeBlocking("DELETE FROM parent WHERE _id = 7")

        val orphans = driver.countBlocking("SELECT count(*) FROM child WHERE parent_id = 7")
        assertEquals(
            "Deleting the parent left the child behind, so ON DELETE CASCADE did not fire and an orphan is how a row goes missing rather than how it is cleaned up.",
            0L,
            orphans,
        )
    }

    @Test
    fun theSameProbeAcceptsTheRowWhenEnforcementIsOff() {
        val driver = openDriver("fk_probe_unenforced.db", enforced = false)
        driver.executeBlocking("INSERT INTO child (parent_id) VALUES (404)")
    }

    private fun openDriver(name: String, enforced: Boolean): SqlDriver {
        listOf(name, "$name-wal", "$name-shm").forEach { context.getDatabasePath(it).delete() }
        val driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.File(context.getDatabasePath(name).absolutePath),
            schema = ProbeSchema,
            configuration = AndroidxSqliteConfiguration(
                isForeignKeyConstraintsEnabled = enforced,
            ),
        )
        opened += driver to name
        return driver
    }

    private fun SqlDriver.executeBlocking(sql: String) = runBlocking {
        execute(null, sql, 0).await()
    }

    private fun SqlDriver.countBlocking(sql: String): Long = runBlocking {
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                QueryResult.AsyncValue {
                    cursor.next().await()
                    cursor.getLong(0)!!
                }
            },
            parameters = 0,
        ).await()
    }

    private object ProbeSchema : SqlSchema<QueryResult.AsyncValue<Unit>> {
        override val version: Long = 1

        override fun create(driver: SqlDriver) = QueryResult.AsyncValue {
            driver.execute(null, "CREATE TABLE parent (_id INTEGER NOT NULL PRIMARY KEY)", 0).await()
            driver.execute(
                null,
                """
                CREATE TABLE child (
                    _id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    parent_id INTEGER NOT NULL,
                    FOREIGN KEY(parent_id) REFERENCES parent (_id)
                    ON DELETE CASCADE
                )
                """.trimIndent(),
                0,
            ).await()
            Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: app.cash.sqldelight.db.AfterVersion,
        ) = QueryResult.AsyncValue { }
    }
}
