package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** The bookmark write every novel surface uses for a group's copies, over the real SQL. */
class NovelChapterBookmarkBulkTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: NovelChapterRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            repository = NovelChapterRepositoryImpl(database)
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES (1, 'src', 'n-url', 'title', 0, 0, 0, 0)",
                0,
            ).await()
            listOf(1L, 2L, 3L).forEach { id ->
                driver.execute(
                    null,
                    "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                        "chapter_number, source_order, date_fetch, date_upload) " +
                        "VALUES ($id, 1, 'c-url-$id', 'name', 1, 0, 40, $id, 7, 1000, 1000)",
                    0,
                ).await()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `bookmarks every listed chapter and no other`() = runTest {
        repository.setBookmarkBulk(listOf(1L, 3L), bookmark = true)

        listOf(1L, 2L, 3L).map { repository.getById(it)!!.bookmark } shouldBe listOf(true, false, true)
    }

    /** A merged unified-list copy carries a synthetic order, and a bookmark must not write it back. */
    @Test
    fun `leaves read state, text progress and order as they were`() = runTest {
        repository.setBookmarkBulk(listOf(2L), bookmark = true)

        repository.getById(2L)!!.let { Triple(it.read, it.lastTextProgress, it.sourceOrder) } shouldBe
            Triple(true, 40L, 7L)
    }
}
