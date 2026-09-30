package reikai.data.debug

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The debug menu's source conversion moves a gallery and the searches saved on its source, and leaves
 * a gallery the target source already stores where it is instead of failing the whole move.
 */
class DebugDatabaseRepositoryImplTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: DebugDatabaseRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            database = DatabaseBindings.providesDatabase(driver)
            repository = DebugDatabaseRepositoryImpl(database)
            insertManga(id = 1, source = FROM, url = "/g/1/a/")
            insertManga(id = 2, source = FROM, url = "/g/2/b/")
            insertManga(id = 3, source = TO, url = "/g/2/b/")
            driver.execute(
                null,
                "INSERT INTO saved_search(source_key, name) VALUES ('manga:$FROM', 'tagged')",
                0,
            ).await()
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a gallery moves to the target source`() = runTest {
        repository.migrateSource(FROM, TO)

        sourceOf(1) shouldBe TO
    }

    @Test
    fun `a gallery the target source already stores stays on its own`() = runTest {
        repository.migrateSource(FROM, TO)

        sourceOf(2) shouldBe FROM
    }

    @Test
    fun `a search saved on the old source moves with it`() = runTest {
        repository.migrateSource(FROM, TO)

        savedSearchKey() shouldBe "manga:$TO"
    }

    private suspend fun insertManga(id: Long, source: Long, url: String) {
        driver.execute(
            null,
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                "state_initialized, user_reader_flags, user_chapter_flags, " +
                "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, $source, " +
                "'$url', 't', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
            0,
        ).await()
    }

    private suspend fun sourceOf(id: Long): Long =
        repository.getAllManga().single { it.id == id }.source

    private suspend fun savedSearchKey(): String =
        database.saved_searchQueries.selectAll { _, sourceKey, _, _, _ -> sourceKey }.awaitAsList().single()

    private companion object {
        const val FROM = 100L
        const val TO = 200L
    }
}
