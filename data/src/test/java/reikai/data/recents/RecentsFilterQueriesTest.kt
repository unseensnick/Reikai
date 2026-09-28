package reikai.data.recents

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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

/**
 * The recents feeds filter in SQL, and both content types must answer a filter the same way: the
 * selection is one id space over both libraries, so a rule holding on one side only hides rows
 * silently. Hence a twin per test. The repositories are under test with the queries, being what
 * turns an empty id list into the "no constraint" flag. Category id 0 is the uncategorized
 * sentinel: an entry with no membership rows matches an include set only when 0 is in it.
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

    private suspend fun mangaUpdateIds(
        included: List<Long> = emptyList(),
        excluded: List<Long> = emptyList(),
        started: Boolean? = null,
    ): List<Long> = updates.subscribeAll(
        after = 0,
        limit = 100,
        unread = null,
        started = started,
        bookmarked = null,
        hideExcludedScanlators = false,
        includedCategories = included,
        excludedCategories = excluded,
    ).first().map { it.mangaId }

    private suspend fun novelUpdateIds(
        included: List<Long> = emptyList(),
        excluded: List<Long> = emptyList(),
        started: Boolean? = null,
    ): List<Long> = novels.getFilteredNovelUpdatesAsFlow(
        after = 0,
        limit = 100,
        unread = null,
        started = started,
        bookmarked = null,
        includedCategories = included,
        excludedCategories = excluded,
    ).first().map { it.novelId }

    private suspend fun mangaHistoryIds(
        included: List<Long> = emptyList(),
        excluded: List<Long> = emptyList(),
    ): List<Long> = history.getHistory("", included, excluded).first().map { it.mangaId }

    private suspend fun novelHistoryIds(
        included: List<Long> = emptyList(),
        excluded: List<Long> = emptyList(),
    ): List<Long> = novelHistory.getNovelHistory("", included, excluded).first().map { it.novelId }

    @Test
    fun `an empty selection constrains no manga update`() = runTest {
        seedManga(id = 1, categoryId = 1)
        seedManga(id = 2, categoryId = null)

        // Both rows share a fetch date, and the feed orders by nothing else
        mangaUpdateIds().shouldContainExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `an empty selection constrains no novel update`() = runTest {
        seedNovel(id = 1, categoryId = 2)
        seedNovel(id = 2, categoryId = null)

        novelUpdateIds().shouldContainExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `an included category keeps only its manga`() = runTest {
        seedManga(id = 1, categoryId = 1)
        seedManga(id = 2, categoryId = 3)

        mangaUpdateIds(included = listOf(1)) shouldBe listOf(1L)
    }

    @Test
    fun `an included category keeps only its novels`() = runTest {
        seedNovel(id = 1, categoryId = 2)
        seedNovel(id = 2, categoryId = 3)

        novelUpdateIds(included = listOf(2)) shouldBe listOf(1L)
    }

    @Test
    fun `an excluded category drops its manga`() = runTest {
        seedManga(id = 1, categoryId = 1)
        seedManga(id = 2, categoryId = 3)

        mangaUpdateIds(excluded = listOf(1)) shouldBe listOf(2L)
    }

    @Test
    fun `an excluded category drops its novels`() = runTest {
        seedNovel(id = 1, categoryId = 2)
        seedNovel(id = 2, categoryId = 3)

        novelUpdateIds(excluded = listOf(2)) shouldBe listOf(2L)
    }

    @Test
    fun `an uncategorized manga survives only when the default id is included`() = runTest {
        seedManga(id = 1, categoryId = null)

        mangaUpdateIds(included = listOf(1)) shouldBe emptyList()
        mangaUpdateIds(included = listOf(0)) shouldBe listOf(1L)
        mangaUpdateIds(excluded = listOf(0)) shouldBe emptyList()
    }

    @Test
    fun `an uncategorized novel survives only when the default id is included`() = runTest {
        seedNovel(id = 1, categoryId = null)

        novelUpdateIds(included = listOf(2)) shouldBe emptyList()
        novelUpdateIds(included = listOf(0)) shouldBe listOf(1L)
        novelUpdateIds(excluded = listOf(0)) shouldBe emptyList()
    }

    @Test
    fun `excluding beats including for a manga in both categories`() = runTest {
        seedManga(id = 1, categoryId = 1)
        addMangaCategory(mangaId = 1, categoryId = 3)

        mangaUpdateIds(included = listOf(1), excluded = listOf(3)) shouldBe emptyList()
    }

    @Test
    fun `excluding beats including for a novel in both categories`() = runTest {
        seedNovel(id = 1, categoryId = 2)
        addNovelCategory(novelId = 1, categoryId = 3)

        novelUpdateIds(included = listOf(2), excluded = listOf(3)) shouldBe emptyList()
    }

    @Test
    fun `not started hides a read manga chapter`() = runTest {
        seedManga(id = 1, categoryId = null, read = true)

        mangaUpdateIds(started = false) shouldBe emptyList()
    }

    @Test
    fun `not started hides a read novel chapter`() = runTest {
        seedNovel(id = 1, categoryId = null, read = true)

        novelUpdateIds(started = false) shouldBe emptyList()
    }

    @Test
    fun `not started keeps an untouched manga chapter`() = runTest {
        seedManga(id = 1, categoryId = null)

        mangaUpdateIds(started = false) shouldBe listOf(1L)
    }

    @Test
    fun `not started keeps an untouched novel chapter`() = runTest {
        seedNovel(id = 1, categoryId = null)

        novelUpdateIds(started = false) shouldBe listOf(1L)
    }

    @Test
    fun `an included category keeps only its manga history`() = runTest {
        seedManga(id = 1, categoryId = 1, withHistory = true)
        seedManga(id = 2, categoryId = 3, withHistory = true)

        mangaHistoryIds() shouldBe listOf(1L, 2L)
        mangaHistoryIds(included = listOf(1)) shouldBe listOf(1L)
        mangaHistoryIds(excluded = listOf(1)) shouldBe listOf(2L)
    }

    @Test
    fun `an included category keeps only its novel history`() = runTest {
        seedNovel(id = 1, categoryId = 2, withHistory = true)
        seedNovel(id = 2, categoryId = 3, withHistory = true)

        novelHistoryIds() shouldBe listOf(1L, 2L)
        novelHistoryIds(included = listOf(2)) shouldBe listOf(1L)
        novelHistoryIds(excluded = listOf(2)) shouldBe listOf(2L)
    }

    @Test
    fun `a read entry that was never added to the library reads as uncategorized`() = runTest {
        // historyView has no favorite gate, so these rows are in the feed with no membership rows at
        // all. Accepted behaviour, pinned here because it is invisible until someone hits it.
        seedManga(id = 1, categoryId = null, favorite = false, withHistory = true)
        seedNovel(id = 1, categoryId = null, favorite = false, withHistory = true)

        mangaHistoryIds(included = listOf(1)) shouldBe emptyList()
        mangaHistoryIds(included = listOf(0)) shouldBe listOf(1L)
        novelHistoryIds(included = listOf(2)) shouldBe emptyList()
        novelHistoryIds(included = listOf(0)) shouldBe listOf(1L)
    }

    // A favorited entry with one chapter fetched after it was added, which is what puts it in the
    // updates feed. Category ids 1 and 2 are seeded manga-side and novel-side, 3 is universal.
    private suspend fun seedManga(
        id: Long,
        categoryId: Long?,
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
        if (categoryId != null) addMangaCategory(id, categoryId)
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
        categoryId: Long?,
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
        if (categoryId != null) addNovelCategory(id, categoryId)
        if (withHistory) {
            driver.execute(
                null,
                "INSERT INTO novel_history(_id, chapter_id, last_read, time_read) VALUES ($id, $id, ${2000 - id}, 0)",
                0,
            ).await()
        }
    }

    private suspend fun addMangaCategory(mangaId: Long, categoryId: Long) {
        ensureCategory(categoryId)
        driver.execute(
            null,
            "INSERT INTO manga_category(manga_id, category_id) VALUES ($mangaId, $categoryId)",
            0,
        ).await()
    }

    private suspend fun addNovelCategory(novelId: Long, categoryId: Long) {
        ensureCategory(categoryId)
        driver.execute(
            null,
            "INSERT INTO novels_categories(novel_id, category_id) VALUES ($novelId, $categoryId)",
            0,
        ).await()
    }

    private suspend fun ensureCategory(id: Long) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO category(id, name, `order`, flags, content_type) " +
                "VALUES ($id, 'cat $id', $id, 0, 0)",
            0,
        ).await()
    }

    private fun Boolean.toSql(): Int = if (this) 1 else 0
}
