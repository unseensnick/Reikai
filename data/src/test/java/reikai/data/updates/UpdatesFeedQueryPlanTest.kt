package reikai.data.updates

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.RecordingDriver
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.queryPlan
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.updates.UpdatesRepositoryImpl

/**
 * The updates feed stays subscribed for the life of the process (the unified widget holds it), so it
 * re-runs on every chapter write. Each run must walk the chapters newest fetch first and stop at the
 * limit, which takes an index on the fetch date; without one it reads every library chapter and sorts.
 */
class UpdatesFeedQueryPlanTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the updates feed walks chapters by fetch date`(type: ContentType) = runTest {
        Database.Schema.create(driver).await()
        val issued = mutableListOf<String>()
        val database = DatabaseBindings.providesDatabase(RecordingDriver(driver, issued))
        when (type) {
            ContentType.MANGA -> UpdatesRepositoryImpl(database).subscribeAll(
                after = 0,
                limit = 500,
                unread = true,
                started = null,
                bookmarked = null,
                hideExcludedScanlators = false,
                includedCategories = emptyList(),
                excludedCategories = emptyList(),
            ).first()
            else -> NovelRepositoryImpl(database).getFilteredNovelUpdatesAsFlow(
                after = 0,
                limit = 500,
                unread = true,
                started = null,
                bookmarked = null,
                includedCategories = emptyList(),
                excludedCategories = emptyList(),
            ).first()
        }

        val plan = driver.queryPlan(issued.single())

        withClue(plan.joinToString("\n")) {
            plan shouldNotContain "USE TEMP B-TREE FOR ORDER BY"
        }
    }
}
