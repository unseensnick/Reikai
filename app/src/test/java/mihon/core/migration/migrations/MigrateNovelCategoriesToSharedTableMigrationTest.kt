package mihon.core.migration.migrations

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.db.SqlDelightTransactions
import reikai.domain.category.CategoryContentType
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore
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
 * The migrator stamps its version only after the whole chain resolves, so a kill later in the chain
 * runs this again. The flag translation is a swap and the id shift has no ceiling, so a second run
 * must change nothing. Only the moved novel rows carry the novel flag layout.
 */
class MigrateNovelCategoriesToSharedTableMigrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var categories: CategoryRepositoryImpl
    private lateinit var migration: MigrateNovelCategoriesToSharedTableMigration
    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)

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
            database.categoriesQueries.insert(name = "Novels", flags = NOVEL_DOWNLOADED, contentType = 2L)
            database.categoriesQueries.insert(name = "Manga", flags = NOVEL_DOWNLOADED, contentType = 1L)
            novelPreferences.defaultNovelCategory().set(5)
            categories = CategoryRepositoryImpl(database)
            migration = MigrateNovelCategoriesToSharedTableMigration(
                categories,
                SqlDelightTransactions(database),
                novelPreferences,
                store,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private suspend fun runTwice() {
        repeat(2) { migration.invoke(MigrationContext(dryrun = false, previousVersion = 185)) }
    }

    private suspend fun flagsOf(contentType: Long) =
        categories.getUnfiltered().single { it.contentType == contentType }.flags

    @Test
    fun `a second run leaves the translated sort flags alone`() = runTest {
        runTwice()

        flagsOf(CategoryContentType.NOVEL) shouldBe MANGA_DOWNLOADED
    }

    @Test
    fun `a manga category keeps its sort flags`() = runTest {
        migration.invoke(MigrationContext(dryrun = false, previousVersion = 185))

        flagsOf(CategoryContentType.MANGA) shouldBe NOVEL_DOWNLOADED
    }

    @Test
    fun `a second run leaves the shifted category ids alone`() = runTest {
        runTwice()

        novelPreferences.defaultNovelCategory().get() shouldBe
            5 + MigrateNovelCategoriesToSharedTableMigration.NOVEL_CATEGORY_ID_MIGRATION_OFFSET.toInt()
    }

    private companion object {
        // The novel layout's Downloaded sort type, and the manga layout's.
        const val NOVEL_DOWNLOADED = 0b100000L
        const val MANGA_DOWNLOADED = 0b100100L
    }
}
