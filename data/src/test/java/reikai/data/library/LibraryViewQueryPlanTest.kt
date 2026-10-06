package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.data.queryPlan
import tachiyomi.data.Database

/**
 * Both library views re-run on every write to their tables, so their chapter and category aggregates must
 * reach rows through the favourites rather than scan whole tables, including entries outside the library.
 */
class LibraryViewQueryPlanTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["libraryView", "novelLibraryView"])
    fun `the library view reads only favourites`(view: String) = runTest {
        Database.Schema.create(driver).await()

        val plan = driver.queryPlan("SELECT * FROM $view")

        withClue(plan.joinToString("\n")) {
            plan.filter { it.startsWith("SCAN") }.shouldBeEmpty()
        }
    }
}
