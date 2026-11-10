package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.library.ContentType
import reikai.novel.host.ChapterItem
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * A paged novel's chapters numbered and ordered page by page, in any page order, against the same chapters
 * synced as one list. Paging is the novel transport alone: a manga source hands its whole list over at once.
 */
class PagedChapterNumberingTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        listOf(WHOLE, PAGED).forEach { id ->
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($id, 'src', 'u$id', 'T', 0, 0, 0, 0)",
                0,
            ).await()
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["1,2", "2,1"])
    fun `a paged sync in either page order numbers each chapter as a whole-list sync does`(order: String) =
        runTest {
            sync(WHOLE, PAGE_ONE + PAGE_TWO, page = null)

            order.split(',').forEach { page -> sync(PAGED, if (page == "1") PAGE_ONE else PAGE_TWO, page) }

            numbers(PAGED) shouldBe numbers(WHOLE)
        }

    @ParameterizedTest
    @ValueSource(strings = ["1,2", "2,1"])
    fun `a paged sync in either page order stores each chapter's source order as a whole-list sync does`(
        order: String,
    ) = runTest {
        sync(WHOLE, PAGE_ONE + PAGE_TWO, page = null)

        order.split(',').forEach { page -> sync(PAGED, if (page == "1") PAGE_ONE else PAGE_TWO, page) }

        sourceOrders(PAGED) shouldBe sourceOrders(WHOLE)
    }

    @Test
    fun `a page sync that renumbers a chapter on another page keeps its correction`() = runTest {
        sync(PAGED, PAGE_ONE, page = "1")
        overrides.set(ContentType.NOVELS, PAGED, "/calm", 7.0)

        sync(PAGED, PAGE_TWO, page = "2")

        numbers(PAGED)["/calm"] shouldBe 7.0
    }

    private val overrides get() = ChapterNumberOverrideRepositoryImpl(database)

    private suspend fun sync(novelId: Long, items: List<ChapterItem>, page: String?) {
        syncChaptersWithNovelSource(
            items,
            NovelRepositoryImpl(database).getById(novelId)!!,
            NovelChapterRepositoryImpl(database),
            NovelRepositoryImpl(database),
            LibraryPreferences(InMemoryPreferenceStore()),
            overrides,
            InMemoryChapterCopyMarks(),
            page = page,
        )
    }

    private suspend fun numbers(novelId: Long): Map<String, Double> =
        NovelChapterRepositoryImpl(database).getByNovelId(novelId).associate { it.url to it.chapterNumber }

    private suspend fun sourceOrders(novelId: Long): Map<String, Long> =
        NovelChapterRepositoryImpl(database).getByNovelId(novelId).associate { it.url to it.sourceOrder }

    private companion object {
        const val WHOLE = 1L
        const val PAGED = 2L

        // Oldest first, as plugins list them. "The Calm" and "The Storm" sit either side of the page
        // boundary with no number; "Finale" carries only the plugin's own.
        val PAGE_ONE = listOf(
            ChapterItem(name = "Chapter 1", path = "/1"),
            ChapterItem(name = "Chapter 2", path = "/2"),
            ChapterItem(name = "The Calm", path = "/calm"),
        )
        val PAGE_TWO = listOf(
            ChapterItem(name = "The Storm", path = "/storm"),
            ChapterItem(name = "Chapter 3", path = "/3"),
            ChapterItem(name = "Finale", path = "/fin", chapterNumber = 4.0),
        )
    }
}
