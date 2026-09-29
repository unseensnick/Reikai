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
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

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
            database = DatabaseBindings.providesDatabase(driver)
            driver.execute(
                null,
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($ENTRY_ID, 1, " +
                    "'m', 't', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                0,
            ).await()
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES ($ENTRY_ID, 'src', 'n', 't', 0, 0, 0, 0)",
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

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `linking a category the entry already has keeps one link`(type: ContentType) = runTest {
        val categoryId = categoryOf(ownType(type))
        link(type, categoryId)

        link(type, categoryId)

        linkCount(type) shouldBe 1L
    }

    private var nextCategoryId = 10L

    private suspend fun categoryOf(contentType: Long): Long {
        val id = nextCategoryId++
        driver.execute(
            null,
            "INSERT INTO category(id, name, `order`, flags, content_type) VALUES ($id, 'c$id', $id, 0, " +
                "$contentType)",
            0,
        ).await()
        return id
    }

    private suspend fun link(type: ContentType, categoryId: Long) {
        when (type) {
            ContentType.MANGA -> database.manga_categoryQueries.insert(ENTRY_ID, categoryId)
            else -> database.novels_categoriesQueries.insert(ENTRY_ID, categoryId)
        }
    }

    private suspend fun linkCount(type: ContentType): Long {
        val table = if (type == ContentType.MANGA) "manga_category" else "novels_categories"
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
