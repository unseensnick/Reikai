package reikai.data.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.data.RecordingDriver
import reikai.data.queryPlan
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl

/**
 * The gallery-version reconciliation looks up every chapter of a gallery chain by url alone, once per
 * chapter, so each lookup must find its rows by index. chapter's UNIQUE(manga_id, remote_url) leads with
 * the entry and cannot serve it.
 */
class ChapterByUrlQueryPlanTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a chapter lookup by url alone reads no whole table`() = runTest {
        Database.Schema.create(driver).await()
        val issued = mutableListOf<String>()
        ChapterRepositoryImpl(DatabaseBindings.providesDatabase(RecordingDriver(driver, issued)))
            .getChapterByUrl("/g/1/abc/")

        val plan = driver.queryPlan(issued.single())

        withClue(plan.joinToString("\n")) {
            plan.filter { it.startsWith("SCAN") }.shouldBeEmpty()
        }
    }
}
