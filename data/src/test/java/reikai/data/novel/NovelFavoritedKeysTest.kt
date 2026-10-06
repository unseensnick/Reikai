package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.FavoritedNovels
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The library keys the browse, search and feed lists badge their rows from, over the real SQL. Browsing
 * stores every result it shows, so a write to a novel outside the library must not wake those lists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NovelFavoritedKeysTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: NovelRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            repository = NovelRepositoryImpl(DatabaseBindings.providesDatabase(driver))
            repository.insert(novel("in-library", favoriteAt = 1L))
            repository.insert(novel("browsed", favoriteAt = null))
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `storing a novel outside the library leaves the keys silent`() = runTest {
        emissionsAcross { repository.insert(novel("another browsed", favoriteAt = null)) }.size shouldBe 1
    }

    @Test
    fun `adding a novel to the library emits keys that contain it`() = runTest {
        val browsed = repository.getByUrlAndSource("browsed", SOURCE)!!

        emissionsAcross { repository.update(NovelUpdate(browsed.id) { favoriteAt = 2L }) }
            .last().contains(SOURCE, "browsed") shouldBe true
    }

    /** A listed result draws its cover from the stored library row, so a cover change has to reach it. */
    @Test
    fun `a library novel's cover change emits its new row`() = runTest {
        val inLibrary = repository.getByUrlAndSource("in-library", SOURCE)!!

        emissionsAcross { repository.update(NovelUpdate(inLibrary.id) { coverLastModified = 5L }) }
            .last().stored(SOURCE, "in-library")?.coverLastModified shouldBe 5L
    }

    /** Every value the keys flow sent, the one on collection included, across [write]. */
    private fun TestScope.emissionsAcross(write: suspend () -> Unit): List<FavoritedNovels> {
        val emitted = mutableListOf<FavoritedNovels>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.getFavoritedKeysAsFlow().collect { emitted += it }
        }
        advanceUntilIdle()
        launch { write() }
        advanceUntilIdle()
        return emitted
    }

    private fun novel(url: String, favoriteAt: Long?) =
        Novel.create().copy(source = SOURCE, url = url, title = url, favoriteAt = favoriteAt)

    private companion object {
        const val SOURCE = "src"
    }
}
