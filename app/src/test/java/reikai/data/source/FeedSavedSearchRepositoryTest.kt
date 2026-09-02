package reikai.data.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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

        repository.countGlobal() shouldBe 1L
    }

    @Test
    fun `reordering moves a row and keeps the rest in their relative order`() = runTest {
        val first = repository.insert(mangaSource, savedSearchId = null, global = true)
        val second = repository.insert(novelSource, savedSearchId = null, global = true)
        val third = repository.insert(SourceKey.Manga(42L), savedSearchId = null, global = true)

        repository.updateOrders(listOf(third, first, second))

        repository.subscribeGlobal().first().map { it.id } shouldBe listOf(third, first, second)
    }

    @Test
    fun `reordering leaves a row it was not given alone`() = runTest {
        // A row of the other scope is never listed beside these, so a renumber must not move it. It
        // would be renumbered to a position in this list, which is not a position it has.
        val untouched = repository.insert(mangaSource, savedSearchId = null, global = false)
        val first = repository.insert(novelSource, savedSearchId = null, global = true)
        val second = repository.insert(SourceKey.Manga(42L), savedSearchId = null, global = true)

        repository.updateOrders(listOf(second, first))

        repository.getAll().single { it.id == untouched }.feedOrder shouldBe 1L
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
    fun `adding a row that is already there returns it rather than doubling it`() = runTest {
        val searchId = savedSearches.insert(mangaSource, "Ongoing", null, null)
        val first = repository.insert(mangaSource, savedSearchId = searchId, global = true)

        val second = repository.insert(mangaSource, savedSearchId = searchId, global = true)

        second shouldBe first
        repository.getAll() shouldHaveSize 1
    }

    @Test
    fun `a plain listing row is deduped too`() = runTest {
        // The null saved search is the case a `saved_search = :value` comparison gets wrong: NULL is
        // never equal to NULL, so the plain row would be addable again and again.
        val first = repository.insert(novelSource, savedSearchId = null, global = true)

        val second = repository.insert(novelSource, savedSearchId = null, global = true)

        second shouldBe first
        repository.getAll() shouldHaveSize 1
    }

    @Test
    fun `two rows on one source stay apart when they carry different searches`() = runTest {
        val ongoing = savedSearches.insert(mangaSource, "Ongoing", null, null)
        val done = savedSearches.insert(mangaSource, "Done", null, null)

        repository.insert(mangaSource, savedSearchId = ongoing, global = true)
        repository.insert(mangaSource, savedSearchId = done, global = true)

        repository.getAll() shouldHaveSize 2
    }

    @Test
    fun `a row whose stored source no longer parses is left out`() = runTest {
        // Beside a good row: one unreadable row must cost its own place in the feed and no other.
        repository.insert(mangaSource, savedSearchId = null, global = true)
        driver.execute(
            null,
            "INSERT INTO feed_saved_search(source_key, saved_search, global, feed_order) " +
                "VALUES ('not a source key', NULL, 1, 9)",
            0,
        ).await()

        repository.getAll().map { it.sourceKey } shouldBe listOf(mangaSource)
    }

    @Test
    fun `deleting a row leaves the saved search it pointed at`() = runTest {
        val searchId = savedSearches.insert(mangaSource, "Ongoing", null, null)
        val rowId = repository.insert(mangaSource, savedSearchId = searchId, global = true)

        repository.delete(rowId)

        savedSearches.getBySource(mangaSource).map { it.name } shouldBe listOf("Ongoing")
    }
}
