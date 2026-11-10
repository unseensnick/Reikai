package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ManualOrderPosition
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** A category's Manual order on the real schema: written whole, kept per type, gone with its category. */
class LibraryManualOrderRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: LibraryManualOrderRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = DatabaseBindings.providesDatabase(driver)
            driver.execute(
                null,
                "INSERT INTO category(id, name, `order`, flags, content_type) VALUES ($CATEGORY, 'Reading', 1, 0, 0)",
                0,
            ).await()
            repository = LibraryManualOrderRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private fun place(type: ContentType, id: Long, position: Long) = ManualOrderPosition(type, id, CATEGORY, position)

    @Test
    fun `replacing a category's order drops the places it had`() = runTest {
        repository.replaceCategory(CATEGORY, listOf(place(ContentType.MANGA, 1, 0), place(ContentType.MANGA, 2, 1)))

        repository.replaceCategory(CATEGORY, listOf(place(ContentType.MANGA, 2, 0)))

        repository.getCategory(CATEGORY) shouldBe listOf(place(ContentType.MANGA, 2, 0))
    }

    @Test
    fun `places a restore writes follow the device's own instead of tying with them`() = runTest {
        repository.upsert(listOf(place(ContentType.MANGA, 1, 0), place(ContentType.MANGA, 2, 1)))

        repository.yieldToRestore()
        repository.upsert(listOf(place(ContentType.MANGA, 3, 0), place(ContentType.MANGA, 4, 1)))

        repository.getCategory(CATEGORY).sortedBy { it.position }.map { it.entryId } shouldBe listOf(1L, 2L, 3L, 4L)
    }

    /** A manga and a novel can share a row id, so each keeps its own place. */
    @Test
    fun `a manga and a novel with one id keep separate places`() = runTest {
        repository.upsert(listOf(place(ContentType.MANGA, 1, 0), place(ContentType.NOVELS, 1, 1)))

        repository.getForEntries(ContentType.NOVELS, listOf(1)) shouldBe listOf(place(ContentType.NOVELS, 1, 1))
    }

    @Test
    fun `deleting a category drops its order`() = runTest {
        repository.replaceCategory(CATEGORY, listOf(place(ContentType.MANGA, 1, 0)))

        driver.execute(null, "DELETE FROM category WHERE id = $CATEGORY", 0).await()

        repository.getCategory(CATEGORY) shouldBe emptyList()
    }

    private companion object {
        const val CATEGORY = 7L
    }
}
