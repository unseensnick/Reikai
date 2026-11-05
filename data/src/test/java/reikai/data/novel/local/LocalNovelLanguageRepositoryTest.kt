package reikai.data.novel.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** A local novel's language as its files name it, kept as the code the sources use. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalNovelLanguageRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var languages: LocalNovelLanguageRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            languages = LocalNovelLanguageRepositoryImpl(DatabaseBindings.providesDatabase(driver))
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a book's tag is kept as the code sources use`() = runTest {
        languages.set("Book", "ja-JP")

        languages.get("Book") shouldBe "ja"
    }

    @Test
    fun `a tag naming no language forgets the one read before`() = runTest {
        languages.set("Book", "ja")
        languages.set("Book", "und")

        languages.get("Book") shouldBe null
    }

    /** Every local novel's refresh writes its row, and the novel library rebuilds on each emission. */
    @Test
    fun `reading a series again with the same language does not re-emit`() = runTest {
        languages.set("Book", "ja")
        var emitted = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            languages.subscribeAll().collect { emitted++ }
        }
        advanceUntilIdle()

        languages.set("Book", "ja")
        advanceUntilIdle()

        emitted shouldBe 1
    }
}
