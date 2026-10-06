package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.RecordingDriver
import reikai.domain.novel.model.Novel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * What Browse Migrate's novel half reads: each source's library novels and their count. Both read the
 * novels table alone, since the library view re-runs on every chapter, history or category write.
 */
class NovelFavoritesBySourceTest {

    private val driver = RecordingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    private lateinit var repository: NovelRepositoryImpl

    @BeforeEach
    fun setUp() = runTest {
        Database.Schema.create(driver).await()
        repository = NovelRepositoryImpl(DatabaseBindings.providesDatabase(driver))
        repository.insert(novel("a", "beta", favoriteAt = 1L))
        repository.insert(novel("a", "Alpha", favoriteAt = 1L))
        repository.insert(novel("a", "Browsed", favoriteAt = null))
        repository.insert(novel("b", "Other", favoriteAt = 1L))
    }

    @AfterEach
    fun tearDown() = driver.close()

    @Test
    fun `each source's library novels are counted, browsed ones left out`() = runTest {
        repository.getSourcesWithLibraryNovelAsFlow().first().toMap() shouldBe mapOf("a" to 2L, "b" to 1L)
    }

    @Test
    fun `a source's favorites are its library novels alone`() = runTest {
        repository.getFavoritesBySourceAsFlow("a").first().map { it.title }.toSet() shouldBe setOf("Alpha", "beta")
    }

    @Test
    fun `the per-source counts re-read only when the novels table changes`() = runTest {
        repository.getSourcesWithLibraryNovelAsFlow().first()

        driver.listened shouldBe setOf("novels")
    }

    @Test
    fun `a source's favorites re-read only when the novels table changes`() = runTest {
        repository.getFavoritesBySourceAsFlow("a").first()

        driver.listened shouldBe setOf("novels")
    }

    private fun novel(source: String, title: String, favoriteAt: Long?) =
        Novel.create().copy(source = source, url = "/$title", title = title, favoriteAt = favoriteAt)
}
