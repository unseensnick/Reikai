package reikai.data.category

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.category.CategoryContentType
import reikai.domain.library.ContentType
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

/**
 * A category link is refused at the query when no picker for that content type could show the
 * category, pinned once over both link tables. Every writer ends at these two inserts, backup restore
 * included, so a link nothing could ever remove cannot be written by any of them.
 */
class CategoryLinkGuardTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = Database(
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
            driver.execute(
                null,
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) VALUES ($ENTRY_ID, 1, 'm', 't', 0, 1, 0, 0, 0, 0, 0)",
                0,
            ).await()
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                    "VALUES ($ENTRY_ID, 'src', 'n', 't', 0, 1, 0, 0)",
                0,
            ).await()
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a universal category is linked`(type: ContentType) = runTest {
        link(type, categoryOf(CategoryContentType.UNIVERSAL))

        linkCount(type) shouldBe 1L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a category of the entry's own type is linked`(type: ContentType) = runTest {
        link(type, categoryOf(ownType(type)))

        linkCount(type) shouldBe 1L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a category only the other type's picker shows is refused`(type: ContentType) = runTest {
        link(type, categoryOf(otherType(type)))

        linkCount(type) shouldBe 0L
    }

    private var nextCategoryId = 10L

    private suspend fun categoryOf(contentType: Long): Long {
        val id = nextCategoryId++
        driver.execute(
            null,
            "INSERT INTO categories(_id, name, sort, flags, content_type) VALUES ($id, 'c$id', $id, 0, $contentType)",
            0,
        ).await()
        return id
    }

    private suspend fun link(type: ContentType, categoryId: Long) {
        when (type) {
            ContentType.MANGA -> database.mangas_categoriesQueries.insert(ENTRY_ID, categoryId)
            else -> database.novels_categoriesQueries.insert(ENTRY_ID, categoryId)
        }
    }

    private suspend fun linkCount(type: ContentType): Long {
        val table = if (type == ContentType.MANGA) "mangas_categories" else "novels_categories"
        return driver.executeQuery(
            null,
            "SELECT count(*) FROM $table",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0,
        ).await()!!
    }

    private fun ownType(type: ContentType) =
        if (type == ContentType.MANGA) CategoryContentType.MANGA else CategoryContentType.NOVEL

    private fun otherType(type: ContentType) =
        if (type == ContentType.MANGA) CategoryContentType.NOVEL else CategoryContentType.MANGA

    private companion object {
        const val ENTRY_ID = 1L
    }
}
