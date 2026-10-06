package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** Every whole-row write stores every column of the chapter it is given, over the real SQL. */
class NovelChapterRowWriteTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: NovelChapterRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            repository = NovelChapterRepositoryImpl(DatabaseBindings.providesDatabase(driver))
            listOf(1L, 2L).forEach { id ->
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                        "favorite_at) VALUES ($id, 'src', 'n-url-$id', 'title', 0, 0, 0, 0)",
                    0,
                ).await()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private val blank = NovelChapter(
        id = -1L, novelId = 1L, url = "c-url", name = "name", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L, dateFetch = 0L, dateUpload = 0L, page = "",
    )

    /** A value in every column that differs from [blank]'s, so a column a write leaves out shows. */
    private fun NovelChapter.everyColumnChanged() = copy(
        novelId = 2L, url = "other-url", name = "other", read = true, bookmark = true, lastTextProgress = 55L,
        chapterNumber = 9.5, sourceOrder = 4L, dateFetch = 3000L, dateUpload = 4000L, page = "2",
        scanlator = "Group",
    )

    @Test
    fun `an inserted chapter stores every column`() = runTest {
        val written = blank.everyColumnChanged()

        val id = repository.insert(written)!!

        repository.getById(id) shouldBe written.copy(id = id)
    }

    @Test
    fun `a chapter a source sync adds stores every column`() = runTest {
        val written = blank.everyColumnChanged()

        val id = repository.updateFromRemote(emptyList(), listOf(written), emptyList()).single().id

        repository.getById(id) shouldBe written.copy(id = id)
    }

    @Test
    fun `an updated chapter stores every column`() = runTest {
        val id = repository.insert(blank)!!
        val written = blank.copy(id = id).everyColumnChanged()

        repository.update(written)

        repository.getById(id) shouldBe written
    }

    @Test
    fun `a batch-updated chapter stores every column`() = runTest {
        val id = repository.insert(blank)!!
        val written = blank.copy(id = id).everyColumnChanged()

        repository.updateAll(listOf(written))

        repository.getById(id) shouldBe written
    }
}
