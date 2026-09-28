package mihon.data.extension.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
 * A store removed while its index is still being fetched stays removed (mihon fc5592ff7). The service is
 * the network boundary, so it is the one mock.
 */
class ExtensionStoreRefreshTest {

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

    @Test
    fun `a store removed while its refresh is in flight stays removed`() = runTest {
        val service = mockk<ExtensionStoreService> {
            coEvery { fetch(URL) } coAnswers {
                database.extension_storeQueries.delete(URL)
                Result.success(store)
            }
        }
        val repository = ExtensionStoreRepositoryImpl(service, database)
        repository.insertFromPreference(URL, "Store")

        repository.refreshAll()

        database.extension_storeQueries.getAll().awaitAsList() shouldBe emptyList()
    }

    private companion object {
        const val URL = "https://example.org/index.min.json"

        val store = ExtensionStore(
            indexUrl = URL,
            name = "Store",
            badgeLabel = "Store",
            signingKey = "key",
            contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )
    }
}
