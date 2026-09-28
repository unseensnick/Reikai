package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The ranking table and the two staleness views, run on a created schema. That an upgraded install
 * reaches the same schema is checked by `verifySqlDelightMigration` against the committed `43.db`
 * snapshot, not here: a fixture faking an older schema breaks on every later migration.
 */
class MergedGroupRankingTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var groups: MergeGroupRepositoryImpl
    private lateinit var units: MergedChapterUnitRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            val database = DatabaseBindings.providesDatabase(driver)
            groups = MergeGroupRepositoryImpl(database)
            units = MergedChapterUnitRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a group records the ranking it was stitched under`() = runTest {
        val group = twoMemberGroup()

        units.replaceGroup(ContentType.MANGA, group, emptyList(), ranking = "2;1")

        units.getRankings() shouldBe mapOf(group to "2;1")
    }

    @Test
    fun `a group with unstitched chapters reads as stale`() = runTest {
        val group = twoMemberGroup()
        driver.execute(
            null,
            "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES (10, 1, " +
                "'/1/10', 'Chapter 1', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}')",
            0,
        ).await()

        units.isStale(ContentType.MANGA, group) shouldBe true
    }

    private suspend fun twoMemberGroup(): Long {
        listOf(1L, 2L).forEach { id ->
            driver.execute(
                null,
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, $id, " +
                    "'m-url-$id', 'title', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                0,
            ).await()
        }
        return groups.createGroup(ContentType.MANGA, listOf(1L, 2L))!!
    }
}
