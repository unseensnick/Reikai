package reikai.data.recents

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import kotlin.time.Instant

/**
 * Recents feed filter cases the category conformance (RecentsCategoryFilterConformanceTest, in :app) does
 * not reach: the Started filter on the updates feeds, and a history row whose entry was never added. The
 * repositories are under test with the queries, being what turns an empty id list into the "no constraint"
 * flag. Category id 0 is the uncategorized sentinel.
 */
class RecentsFilterQueriesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var updates: UpdatesRepositoryImpl
    private lateinit var novels: NovelRepositoryImpl
    private lateinit var history: HistoryRepositoryImpl
    private lateinit var novelHistory: NovelHistoryRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = DatabaseBindings.providesDatabase(driver)
            updates = UpdatesRepositoryImpl(database)
            novels = NovelRepositoryImpl(database)
            history = HistoryRepositoryImpl(database)
            novelHistory = NovelHistoryRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private suspend fun mangaUpdateIds(started: Boolean?): List<Long> = updates.subscribeAll(
        after = Instant.fromEpochMilliseconds(0),
        limit = 100,
        unread = null,
        started = started,
        bookmarked = null,
        hideExcludedScanlators = false,
        includedCategories = emptyList(),
        excludedCategories = emptyList(),
    ).first().map { it.mangaId }

    private suspend fun novelUpdateIds(started: Boolean?): List<Long> = novels.getFilteredNovelUpdatesAsFlow(
        after = Instant.fromEpochMilliseconds(0),
        limit = 100,
        unread = null,
        started = started,
        bookmarked = null,
        includedCategories = emptyList(),
        excludedCategories = emptyList(),
    ).first().map { it.novelId }

    private suspend fun mangaHistoryIds(included: List<Long>): List<Long> =
        history.getHistory("", included, emptyList()).first().map { it.mangaId }

    private suspend fun novelHistoryIds(included: List<Long>): List<Long> =
        novelHistory.getNovelHistory("", included, emptyList()).first().map { it.novelId }

    @Test
    fun `not started hides a read manga chapter`() = runTest {
        seedManga(id = 1, read = true)

        mangaUpdateIds(started = false) shouldBe emptyList()
    }

    @Test
    fun `not started hides a read novel chapter`() = runTest {
        seedNovel(id = 1, read = true)

        novelUpdateIds(started = false) shouldBe emptyList()
    }

    @Test
    fun `not started keeps an untouched manga chapter`() = runTest {
        seedManga(id = 1)

        mangaUpdateIds(started = false) shouldBe listOf(1L)
    }

    @Test
    fun `not started keeps an untouched novel chapter`() = runTest {
        seedNovel(id = 1)

        novelUpdateIds(started = false) shouldBe listOf(1L)
    }

    @Test
    fun `a read entry that was never added to the library reads as uncategorized`() = runTest {
        // historyView has no favorite gate, so these rows are in the feed with no membership rows at
        // all. Accepted behaviour, pinned here because it is invisible until someone hits it.
        seedManga(id = 1, favorite = false, withHistory = true)
        seedNovel(id = 1, favorite = false, withHistory = true)

        mangaHistoryIds(included = listOf(1)) shouldBe emptyList()
        mangaHistoryIds(included = listOf(0)) shouldBe listOf(1L)
        novelHistoryIds(included = listOf(2)) shouldBe emptyList()
        novelHistoryIds(included = listOf(0)) shouldBe listOf(1L)
    }

    // An uncategorized entry with one chapter fetched after it was added, which is what puts a favorited
    // one in the updates feed.
    private suspend fun seedManga(
        id: Long,
        read: Boolean = false,
        favorite: Boolean = true,
        withHistory: Boolean = false,
    ) {
        driver.execute(
            null,
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                "state_initialized, user_reader_flags, user_chapter_flags, " +
                "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, 1, " +
                "'m-url-$id', 'title $id', 0, 0, 0, 0, 0, " +
                "CASE WHEN ${favorite.toSql()} = 1 THEN 0 END, 0, 0, '', '{}')",
            0,
        ).await()
        driver.execute(
            null,
            "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES ($id, " +
                "$id, 'c-url-$id', 'name', NULL, ${read.toSql()}, 0, 0, 1.0, 0, 1000, 1000, '{}')",
            0,
        ).await()
        if (withHistory) {
            driver.execute(
                null,
                "INSERT INTO history(id, chapter_id, read_at, read_duration, manga_id) VALUES ($id, $id, " +
                    "${2000 - id}, 0, (SELECT manga_id FROM chapter WHERE id = $id))",
                0,
            ).await()
        }
    }

    private suspend fun seedNovel(
        id: Long,
        read: Boolean = false,
        favorite: Boolean = true,
        withHistory: Boolean = false,
    ) {
        driver.execute(
            null,
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                "favorite_at) VALUES ($id, 'src', 'n-url-$id', 'title $id', 0, 0, 0, " +
                "CASE WHEN ${favorite.toSql()} = 1 THEN 0 END)",
            0,
        ).await()
        driver.execute(
            null,
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                "chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES ($id, $id, 'c-url-$id', 'name', ${read.toSql()}, 0, 0, 1.0, 0, 1000, 1000)",
            0,
        ).await()
        if (withHistory) {
            driver.execute(
                null,
                "INSERT INTO novel_history(_id, chapter_id, last_read, time_read) VALUES ($id, $id, ${2000 - id}, 0)",
                0,
            ).await()
        }
    }

    private fun Boolean.toSql(): Int = if (this) 1 else 0
}
