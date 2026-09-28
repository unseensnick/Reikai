package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter
import tachiyomi.data.Chapters
import tachiyomi.data.Custom_manga_info
import tachiyomi.data.Custom_novel_info
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Novels
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

/** A source sync's removals, inserts and updates land together or not at all, over the real SQL. */
class NovelChapterUpdateFromRemoteTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: NovelChapterRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = Database(
                driver = driver,
                historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
                novelsAdapter = Novels.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
                custom_manga_infoAdapter = Custom_manga_info.Adapter(genreAdapter = StringListColumnAdapter),
                custom_novel_infoAdapter = Custom_novel_info.Adapter(genreAdapter = StringListColumnAdapter),
            )
            repository = NovelChapterRepositoryImpl(database)
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags, " +
                    "date_added) VALUES (1, 'src', 'n-url', 'title', 0, 1, 0, 0, 0)",
                0,
            ).await()
            listOf(1L, 2L).forEach { id ->
                driver.execute(
                    null,
                    "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                        "chapter_number, source_order, date_fetch, date_upload) " +
                        "VALUES ($id, 1, 'c-url-$id', 'name', 1, 0, 40, $id, $id, 1000, 1000)",
                    0,
                ).await()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private fun added(url: String) = NovelChapter(
        id = -1L, novelId = 1L, url = url, name = "new", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 3.0, sourceOrder = 0L, dateFetch = 2000L,
        dateUpload = 2000L, page = "",
    )

    @Test
    fun `a sync removes, adds and renames in one call, keeping the reader's state`() = runTest {
        val renamed = repository.getById(2L)!!.copy(name = "renamed", read = false, lastTextProgress = 0L)

        repository.updateFromRemote(listOf(1L), listOf(added("c-url-3")), listOf(renamed))

        repository.getByNovelId(1L).map { Triple(it.url, it.name, it.read) } shouldBe listOf(
            Triple("c-url-3", "new", false),
            Triple("c-url-2", "renamed", true),
        )
    }

    @Test
    fun `a sync whose update fails leaves nothing of the sync behind`() = runTest {
        driver.execute(
            null,
            "CREATE TRIGGER fail_update BEFORE UPDATE ON novel_chapters BEGIN SELECT RAISE(ABORT, 'boom'); END",
            0,
        ).await()
        val renamed = repository.getById(2L)!!.copy(name = "renamed")

        shouldThrowAny { repository.updateFromRemote(listOf(1L), listOf(added("c-url-3")), listOf(renamed)) }

        repository.getByNovelId(1L).map { it.url } shouldBe listOf("c-url-1", "c-url-2")
    }
}
