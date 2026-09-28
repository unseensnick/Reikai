package reikai.data.category

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl

/**
 * An entry's categories come back in the order the user arranged them, for both types (mihon ed17ece4e).
 * The sort runs against both the ids and the order the links were made, so no join plan passes by luck.
 */
class EntryCategoryOrderConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an entry's categories come back in the user's order`(type: Type) = runTest {
        (
            listOf(
                "INSERT INTO category(id, name, `order`, flags, content_type) VALUES (1, 'A', 3, 0, 0), (2, 'B', " +
                    "2, 0, 0), (3, 'C', 1, 0, 0)",
            ) + type.statements
            ).forEach { driver.execute(null, it, 0).await() }

        type.categoryIds(CategoryRepositoryImpl(database)) shouldBe listOf(3L, 2L, 1L)
    }

    enum class Type(val statements: List<String>) {
        MANGA(
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES (1, 1, 'u', 'T', " +
                    "0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                "INSERT INTO manga_category(manga_id, category_id) VALUES (1, 1), (1, 2), (1, 3)",
            ),
        ) {
            override suspend fun categoryIds(repository: CategoryRepositoryImpl) =
                repository.getCategoriesByMangaId(1L).map { it.id }
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES (1, 'src', 'u', 'T', 0, 0, 0, 0)",
                "INSERT INTO novels_categories(novel_id, category_id) VALUES (1, 1), (1, 2), (1, 3)",
            ),
        ) {
            override suspend fun categoryIds(repository: CategoryRepositoryImpl) =
                repository.getCategoriesByNovelId(1L).map { it.id }
        },
        ;

        abstract suspend fun categoryIds(repository: CategoryRepositoryImpl): List<Long>
    }
}
