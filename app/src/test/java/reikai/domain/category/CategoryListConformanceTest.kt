package reikai.domain.category

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
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Which categories each library lists from the shared table: its own and the universal ones, in the
 * user's order, never the other library's. One row of each content type, plus the system row 0.
 */
class CategoryListConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: CategoryRepository

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        repository = CategoryRepositoryImpl(DatabaseBindings.providesDatabase(driver))
        driver.execute(
            null,
            "INSERT INTO category(id, name, `order`, flags, content_type) VALUES " +
                "(10, 'Shared', 3, 0, ${CategoryContentType.UNIVERSAL}), " +
                "(11, 'Manga', 2, 0, ${CategoryContentType.MANGA}), " +
                "(12, 'Novel', 1, 0, ${CategoryContentType.NOVEL})",
            0,
        ).await()
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Library::class)
    fun `a library lists its own and the shared categories in the user's order`(library: Library) = runTest {
        library.categoryIds(repository) shouldBe library.expected
    }

    enum class Library(val expected: List<Long>) {
        MANGA(listOf(0L, 11L, 10L)) {
            override suspend fun categoryIds(repository: CategoryRepository) =
                GetCategories(repository).await().map { it.id }
        },
        NOVEL(listOf(0L, 12L, 10L)) {
            override suspend fun categoryIds(repository: CategoryRepository) =
                GetNovelCategories(repository).await().map { it.id }
        },
        ;

        abstract suspend fun categoryIds(repository: CategoryRepository): List<Long>
    }
}
