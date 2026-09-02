package reikai.data.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import reikai.domain.source.model.FeedSavedSearch
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
 * Proves feed rows keep their two scopes apart, order by insertion, and that deleting a saved search
 * takes the rows built on it with it. That cascade is what stops a feed row pointing at a search that
 * no longer exists, which would otherwise render as a permanently empty row nobody can remove.
 */
class FeedSavedSearchRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: FeedSavedSearchRepositoryImpl
    private lateinit var savedSearches: SavedSearchRepositoryImpl

    private val mangaSource = SourceKey.Manga(9001L)
    private val novelSource = SourceKey.Novel("novelbin")

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            // The saved-search cascade only fires with foreign keys on, as it does in AppBindings.
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
            repository = FeedSavedSearchRepositoryImpl(database)
            savedSearches = SavedSearchRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a listing row round-trips with no saved search attached`() = runTest {
        val id = repository.insert(mangaSource, savedSearchId = null, global = true)

        repository.getAll().single() shouldBe FeedSavedSearch(
            id = id,
            sourceKey = mangaSource,
            savedSearchId = null,
            global = true,
            feedOrder = 1L,
        )
    }

    @Test
    fun `global rows and a source's own rows do not see each other`() = runTest {
        repository.insert(mangaSource, savedSearchId = null, global = true)
        repository.insert(mangaSource, savedSearchId = null, global = false)

        repository.subscribeGlobal().first().map { it.global } shouldBe listOf(true)
    }

    @Test
    fun `a source's feed holds only that source's rows`() = runTest {
        repository.insert(mangaSource, savedSearchId = null, global = false)
        repository.insert(novelSource, savedSearchId = null, global = false)

        repository.subscribeBySource(novelSource).first().map { it.sourceKey } shouldBe listOf(novelSource)
    }

    @Test
    fun `a source's feed leaves out its own row in the Browse feed`() = runTest {
        // Adding a source to the Browse feed must not also populate that source's own feed, which is
        // the half of the scope rule that reading by source alone cannot catch.
        repository.insert(novelSource, savedSearchId = null, global = true)
        repository.insert(novelSource, savedSearchId = null, global = false)

        repository.subscribeBySource(novelSource).first().map { it.global } shouldBe listOf(false)
    }

    @Test
    fun `each added row takes the next feed order`() = runTest {
        repository.insert(novelSource, savedSearchId = null, global = true)

        repository.insert(mangaSource, savedSearchId = null, global = true)

        repository.getAll().map { it.feedOrder } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `feed order decides the order, not the row id`() = runTest {
        // Written straight to the table, because the repository always appends. Without this the two
        // orderings agree and the ORDER BY is unpinned: rows would read the same either way.
        repository.insert(novelSource, savedSearchId = null, global = true)
        driver.execute(
            null,
            "INSERT INTO feed_saved_search(source_key, saved_search, global, feed_order) " +
                "VALUES ('${mangaSource.serialize()}', NULL, 1, 0)",
            0,
        ).await()

        repository.subscribeGlobal().first().map { it.sourceKey } shouldBe listOf(mangaSource, novelSource)
    }

    @Test
    fun `counting a feed ignores the other scope`() = runTest {
        repository.insert(mangaSource, savedSearchId = null, global = true)
        repository.insert(mangaSource, savedSearchId = null, global = false)
        repository.insert(mangaSource, savedSearchId = null, global = false)

        repository.countGlobal() shouldBe 1L
    }

    @Test
    fun `counting a source's feed ignores other sources and the Browse feed`() = runTest {
        repository.insert(mangaSource, savedSearchId = null, global = false)
        repository.insert(novelSource, savedSearchId = null, global = true)
        repository.insert(novelSource, savedSearchId = null, global = false)

        repository.countBySource(novelSource) shouldBe 1L
    }

    @Test
    fun `deleting a saved search removes the feed row built on it`() = runTest {
        val searchId = savedSearches.insert(novelSource, "Completed", null, null)
        repository.insert(novelSource, savedSearchId = searchId, global = true)

        savedSearches.delete(searchId)

        repository.getAll().shouldBeEmpty()
    }

    @Test
    fun `deleting a saved search leaves a plain listing row alone`() = runTest {
        val searchId = savedSearches.insert(novelSource, "Completed", null, null)
        repository.insert(novelSource, savedSearchId = searchId, global = true)
        repository.insert(novelSource, savedSearchId = null, global = true)

        savedSearches.delete(searchId)

        repository.getAll().map { it.savedSearchId } shouldBe listOf(null)
    }

    @Test
    fun `deleting a row leaves the saved search it pointed at`() = runTest {
        val searchId = savedSearches.insert(mangaSource, "Ongoing", null, null)
        val rowId = repository.insert(mangaSource, savedSearchId = searchId, global = true)

        repository.delete(rowId)

        savedSearches.getById(searchId)!!.name shouldBe "Ongoing"
    }
}
