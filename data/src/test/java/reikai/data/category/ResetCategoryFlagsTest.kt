package reikai.data.category

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.category.CategoryContentType
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
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
import tachiyomi.domain.category.interactor.ResetCategoryFlags

/** Turning off per-category sort clears every override in the shared table, whatever its content type. */
class ResetCategoryFlagsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        runTest {
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
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `clears the override on every content type and keeps the hidden bit`() = runTest {
        val flags = CATEGORY_SORT_CUSTOMIZED or CATEGORY_HIDDEN_MASK
        listOf(CategoryContentType.MANGA, CategoryContentType.NOVEL, CategoryContentType.UNIVERSAL)
            .forEachIndexed { index, type ->
                driver.execute(
                    null,
                    "INSERT INTO categories(_id, name, sort, flags, content_type) " +
                        "VALUES (${index + 10}, 'c$index', $index, $flags, $type)",
                    0,
                ).await()
            }

        ResetCategoryFlags(CategoryRepositoryImpl(database)).await()

        database.categoriesQueries.getAllCategories { id, _, _, flags, _ -> id to flags }
            .awaitAsList()
            .filter { (id, _) -> id >= 10L }
            .map { (_, flags) -> flags } shouldBe List(3) { CATEGORY_HIDDEN_MASK }
    }
}
