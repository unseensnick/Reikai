package mihon.core.migration.migrations

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

/**
 * The migrator stamps its version only after the whole chain resolves, so a kill later in the chain
 * runs this again. The flag translation is a swap and the id shift has no ceiling, so a second run
 * must change nothing.
 */
class MigrateNovelCategoriesToSharedTableMigrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)

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
            database.categoriesQueries.insert(name = "Novels", order = 1L, flags = NOVEL_DOWNLOADED, contentType = 2L)
            novelPreferences.defaultNovelCategory().set(5)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private suspend fun runTwice() {
        val migration = MigrateNovelCategoriesToSharedTableMigration(database, novelPreferences, store)
        repeat(2) { migration.invoke(MigrationContext(dryrun = false, previousVersion = 185)) }
    }

    @Test
    fun `a second run leaves the translated sort flags alone`() = runTest {
        runTwice()

        database.categoriesQueries.getCategoriesByContentType(2L) { _, flags -> flags }.awaitAsOne() shouldBe
            MANGA_DOWNLOADED
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
