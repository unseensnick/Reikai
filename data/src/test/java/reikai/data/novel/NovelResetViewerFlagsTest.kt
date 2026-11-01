package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

class NovelResetViewerFlagsTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private lateinit var repository: NovelRepositoryImpl

    @BeforeEach
    fun setUp() = runTest {
        Database.Schema.create(driver).await()
        repository = NovelRepositoryImpl(DatabaseBindings.providesDatabase(driver))
    }

    @AfterEach
    fun tearDown() = driver.close()

    @Test
    fun `a novel's reader settings go, and nothing else does`() = runTest {
        val id = repository.insert(
            Novel.create().copy(source = "a", url = "/n", title = "N", viewerFlags = 16L, notes = "kept"),
        )!!

        repository.resetViewerFlags()

        repository.getById(id)!!.let { it.viewerFlags to it.notes } shouldBe (0L to "kept")
    }
}
