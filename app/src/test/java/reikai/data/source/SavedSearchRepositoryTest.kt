package reikai.data.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import reikai.domain.source.model.SavedSearch
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
 * Proves a saved search round-trips for both content types, which is the whole point of keying the
 * table on a serialized [SourceKey] rather than on the integer source id upstream uses: a light-novel
 * plugin is identified by a slug that would not survive one.
 */
class SavedSearchRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: SavedSearchRepositoryImpl

    private val mangaSource = SourceKey.Manga(9001L)
    private val novelSource = SourceKey.Novel("novelbin")

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
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
            repository = SavedSearchRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a manga search round-trips with its query and filters`() = runTest {
        val id = repository.insert(mangaSource, "Ongoing action", "action", """[{"_type":"CHECKBOX"}]""")

        repository.getById(id) shouldBe SavedSearch(
            id = id,
            sourceKey = mangaSource,
            name = "Ongoing action",
            query = "action",
            filtersJson = """[{"_type":"CHECKBOX"}]""",
        )
    }

    @Test
    fun `a novel search keeps its plugin slug`() = runTest {
        val id = repository.insert(novelSource, "Completed", null, """{"status":{"value":"1"}}""")

        repository.getById(id)!!.sourceKey shouldBe novelSource
    }

    @Test
    fun `a search with no query or filters round-trips as nulls`() = runTest {
        val id = repository.insert(mangaSource, "Everything", null, null)

        repository.getById(id)!!.query.shouldBeNull()
    }

    @Test
    fun `getBySource returns only that source's searches`() = runTest {
        repository.insert(mangaSource, "Mine", null, null)
        repository.insert(novelSource, "Theirs", null, null)

        repository.getBySource(mangaSource).map { it.name } shouldBe listOf("Mine")
    }

    @Test
    fun `a manga source id cannot collide with a novel slug`() = runTest {
        // The two id spaces are disjoint only because the stored key is prefixed. A raw id column
        // would let these two rows answer each other's lookup.
        repository.insert(SourceKey.Manga(7L), "Manga seven", null, null)
        repository.insert(SourceKey.Novel("7"), "Novel seven", null, null)

        repository.getBySource(SourceKey.Novel("7")).map { it.name } shouldBe listOf("Novel seven")
    }

    @Test
    fun `deleting a search removes it`() = runTest {
        val id = repository.insert(mangaSource, "Temporary", null, null)

        repository.delete(id)

        repository.getById(id).shouldBeNull()
    }

    @Test
    fun `a row whose stored source no longer parses is left out`() = runTest {
        // Corruption must cost that one chip, not the whole list, so the mapper drops the row.
        database.saved_searchQueries.insert("not a source key", "Broken", null, null)

        repository.getAll().shouldBeEmpty()
    }

    @Test
    fun `getAll returns searches of both content types`() = runTest {
        repository.insert(mangaSource, "Manga one", null, null)
        repository.insert(novelSource, "Novel one", null, null)

        repository.getAll().map { it.sourceKey } shouldBe listOf(mangaSource, novelSource)
    }
}
