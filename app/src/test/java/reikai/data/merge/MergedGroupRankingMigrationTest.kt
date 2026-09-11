package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
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
 * Migration 43 on a database that predates it: the ranking table and the two staleness views it adds
 * must be usable afterwards, since an upgraded install never runs `Schema.create`. The starting schema
 * is today's with those three objects dropped, which is what migration 42 left behind.
 */
class MergedGroupRankingMigrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var groups: MergeGroupRepositoryImpl
    private lateinit var units: MergedChapterUnitRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "DROP VIEW mergedChapterStaleView", 0).await()
            driver.execute(null, "DROP VIEW mergedNovelChapterStaleView", 0).await()
            driver.execute(null, "DROP TABLE merged_group_ranking", 0).await()
            Database.Schema.migrate(driver, oldVersion = 43, newVersion = Database.Schema.version).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            val database = Database(
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
            groups = MergeGroupRepositoryImpl(database)
            units = MergedChapterUnitRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a migrated database records the ranking a group was stitched under`() = runTest {
        val group = twoMemberGroup()

        units.replaceGroup(ContentType.MANGA, group, emptyList(), ranking = "2;1")

        units.getRankings() shouldBe mapOf(group to "2;1")
    }

    @Test
    fun `a migrated database sees a group with unstitched chapters as stale`() = runTest {
        val group = twoMemberGroup()
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, " +
                "chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (10, 1, '/1/10', 'Chapter 1', NULL, 0, 0, 0, 1.0, 0, 0, 0)",
            0,
        ).await()

        units.isStale(ContentType.MANGA, group) shouldBe true
    }

    private suspend fun twoMemberGroup(): Long {
        listOf(1L, 2L).forEach { id ->
            driver.execute(
                null,
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) " +
                    "VALUES ($id, $id, 'm-url-$id', 'title', 0, 1, 0, 0, 0, 0, 0)",
                0,
            ).await()
        }
        return groups.createGroup(ContentType.MANGA, listOf(1L, 2L))!!
    }
}
