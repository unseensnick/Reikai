package reikai.data.category

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
                "INSERT INTO categories(_id, name, sort, flags, content_type) VALUES " +
                    "(1, 'A', 3, 0, 0), (2, 'B', 2, 0, 0), (3, 'C', 1, 0, 0)",
            ) + type.statements
            ).forEach { driver.execute(null, it, 0).await() }

        type.categoryIds(CategoryRepositoryImpl(database)) shouldBe listOf(3L, 2L, 1L)
    }

    enum class Type(val statements: List<String>) {
        MANGA(
            listOf(
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, " +
                    "cover_last_modified, date_added) VALUES (1, 1, 'u', 'T', 0, 1, 0, 0, 0, 0, 0)",
                "INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 1), (1, 2), (1, 3)",
            ),
        ) {
            override suspend fun categoryIds(repository: CategoryRepositoryImpl) =
                repository.getCategoriesByMangaId(1L).map { it.id }
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags, " +
                    "date_added) VALUES (1, 'src', 'u', 'T', 0, 1, 0, 0, 0)",
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
