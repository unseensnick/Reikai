package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** The page picker lists a novel's pages in the source's order, over the real SQL. */
class NovelDistinctPagesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: NovelChapterRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            repository = NovelChapterRepositoryImpl(DatabaseBindings.providesDatabase(driver))
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES (1, 'src', 'n-url', 'title', 0, 0, 0, 0)",
                0,
            ).await()
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private suspend fun chapter(page: String, sourceOrder: Long) {
        driver.execute(
            null,
            "INSERT INTO novel_chapters(novel_id, url, name, read, bookmark, last_text_progress, chapter_number, " +
                "source_order, date_fetch, date_upload, page) " +
                "VALUES (1, '$page-$sourceOrder', 'c', 0, 0, 0, 0, $sourceOrder, 0, 0, '$page')",
            0,
        ).await()
    }

    @Test
    fun `volume labels follow the source's order, not the alphabet`() = runTest {
        chapter("Volume 10", 4)
        chapter("Volume 2", 2)
        chapter("Volume 1", 0)
        chapter("Volume 10", 5)
        chapter("Volume 2", 3)
        chapter("Volume 1", 1)

        repository.getDistinctPages(1L) shouldBe listOf("Volume 1", "Volume 2", "Volume 10")
    }

    @Test
    fun `numbered pages run by number whatever order their chapters were stored in`() = runTest {
        chapter("10", 0)
        chapter("2", 1)
        chapter("1", 2)

        repository.getDistinctPages(1L) shouldBe listOf("1", "2", "10")
    }
}
