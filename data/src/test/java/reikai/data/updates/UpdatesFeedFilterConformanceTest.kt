package reikai.data.updates

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelRepositoryImpl
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.updates.UpdatesRepositoryImpl
import kotlin.time.Instant

/**
 * The Updates feed filters answer alike for both types: getRecentUpdatesWithFilters (Mihon's) and
 * getRecentNovelUpdatesWithFilters (Reikai's) are two queries over two tables that must keep one rule.
 * Two differences are deliberate: novels cut off on the fetch date, since LN sources often leave the upload
 * date unknown, and only manga has an excluded-scanlator filter, which every case here leaves off. Category
 * filters alone are pinned to the kernel by RecentsCategoryFilterConformanceTest; here they only combine.
 */
class UpdatesFeedFilterConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a filter combination returns the same chapters for both types`(type: Type, case: Case) = runTest {
        type.seed(driver, type.statements)

        type.chapterIds(database, case) shouldBe case.expected
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a fetched chapter with no upload date shows only for novels`(type: Type) = runTest {
        type.seed(driver, type.statements + type.uploadUnknown)

        type.chapterIds(database, Case.NONE).contains(7L) shouldBe type.cutsOffOnFetch
    }

    /**
     * Entry 1 is in category 1, entry 2 in category 2, entry 3 in none and entry 4 in both. Chapters, all
     * fetched and uploaded after the entry was added: 1 read, 2 started, 3 bookmarked, 4 untouched, 5 read
     * and bookmarked, 6 untouched.
     */
    @Suppress("unused")
    enum class Case(
        val unread: Boolean? = null,
        val started: Boolean? = null,
        val bookmarked: Boolean? = null,
        val included: List<Long> = emptyList(),
        val expected: List<Long>,
    ) {
        NONE(expected = listOf(1L, 2L, 3L, 4L, 5L, 6L)),
        UNREAD(unread = true, expected = listOf(2L, 3L, 4L, 6L)),
        READ(unread = false, expected = listOf(1L, 5L)),
        STARTED(started = true, expected = listOf(2L)),
        NOT_STARTED(started = false, expected = listOf(3L, 4L, 6L)),
        BOOKMARKED(bookmarked = true, expected = listOf(3L, 5L)),
        NOT_BOOKMARKED(bookmarked = false, expected = listOf(1L, 2L, 4L, 6L)),
        UNREAD_UNCATEGORIZED(unread = true, included = listOf(0L), expected = listOf(4L)),
    }

    enum class Type(
        val statements: List<String>,
        val uploadUnknown: String,
        val cutsOffOnFetch: Boolean,
    ) {
        MANGA(
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(1, 1, 'a', 't', 0, 0, 0, 0, 0, 1000, 0, 0, '', '{}'), " +
                    "(2, 1, 'b', 't', 0, 0, 0, 0, 0, 1000, 0, 0, '', '{}'), " +
                    "(3, 1, 'c', 't', 0, 0, 0, 0, 0, 1000, 0, 0, '', '{}'), " +
                    "(4, 1, 'd', 't', 0, 0, 0, 0, 0, 1000, 0, 0, '', '{}')",
                "INSERT INTO manga_category(manga_id, category_id) VALUES (1, 1), (2, 2), (4, 1), (4, 2)",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES " +
                    "(1, 1, 'c1', 'n', NULL, 1, 0, 0, 1.0, 0, 2000, 2000, '{}'), " +
                    "(2, 1, 'c2', 'n', NULL, 0, 0, 5, 2.0, 1, 2000, 2000, '{}'), " +
                    "(3, 2, 'c3', 'n', NULL, 0, 1, 0, 1.0, 0, 2000, 2000, '{}'), " +
                    "(4, 3, 'c4', 'n', NULL, 0, 0, 0, 1.0, 0, 2000, 2000, '{}'), " +
                    "(5, 3, 'c5', 'n', NULL, 1, 1, 0, 2.0, 1, 2000, 2000, '{}'), " +
                    "(6, 4, 'c6', 'n', NULL, 0, 0, 0, 1.0, 0, 2000, 2000, '{}')",
            ),
            "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                "remote_date_upload, remote_memo) VALUES (7, 1, 'c7', 'n', NULL, 0, 0, 0, 3.0, 2, 2000, 0, '{}')",
            cutsOffOnFetch = false,
        ) {
            // The excluded-scanlator filter is manga only, so it stays off to compare like with like.
            override suspend fun query(database: Database, case: Case) = UpdatesRepositoryImpl(database)
                .subscribeAll(
                    after = AFTER,
                    limit = Long.MAX_VALUE,
                    unread = case.unread,
                    started = case.started,
                    bookmarked = case.bookmarked,
                    hideExcludedScanlators = false,
                    includedCategories = case.included,
                    excludedCategories = emptyList(),
                )
                .first()
                .map { it.chapterId }
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 's1', 'a', 't', 0, 0, 0, 1000), (2, 's1', 'b', 't', 0, 0, 0, 1000), " +
                    "(3, 's1', 'c', 't', 0, 0, 0, 1000), (4, 's1', 'd', 't', 0, 0, 0, 1000)",
                "INSERT INTO novels_categories(_id, novel_id, category_id) VALUES " +
                    "(1, 1, 1), (2, 2, 2), (3, 4, 1), (4, 4, 2)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 1, 'c1', 'n', 1, 0, 0, 1.0, 0, 2000, 2000), " +
                    "(2, 1, 'c2', 'n', 0, 0, 5, 2.0, 1, 2000, 2000), " +
                    "(3, 2, 'c3', 'n', 0, 1, 0, 1.0, 0, 2000, 2000), " +
                    "(4, 3, 'c4', 'n', 0, 0, 0, 1.0, 0, 2000, 2000), " +
                    "(5, 3, 'c5', 'n', 1, 1, 0, 2.0, 1, 2000, 2000), " +
                    "(6, 4, 'c6', 'n', 0, 0, 0, 1.0, 0, 2000, 2000)",
            ),
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                "(7, 1, 'c7', 'n', 0, 0, 0, 3.0, 2, 2000, 0)",
            cutsOffOnFetch = true,
        ) {
            override suspend fun query(database: Database, case: Case) = NovelRepositoryImpl(database)
                .getFilteredNovelUpdatesAsFlow(
                    after = AFTER,
                    limit = Long.MAX_VALUE,
                    unread = case.unread,
                    started = case.started,
                    bookmarked = case.bookmarked,
                    includedCategories = case.included,
                    excludedCategories = emptyList(),
                )
                .first()
                .map { it.chapterId }
        },
        ;

        protected abstract suspend fun query(database: Database, case: Case): List<Long>

        suspend fun chapterIds(database: Database, case: Case) = query(database, case).sorted()

        suspend fun seed(driver: JdbcSqliteDriver, statements: List<String>) {
            CATEGORIES.forEach { driver.execute(null, it, 0).await() }
            statements.forEach { driver.execute(null, it, 0).await() }
        }
    }

    companion object {
        private val AFTER = Instant.fromEpochMilliseconds(500)

        private val CATEGORIES = listOf(
            "INSERT INTO category(id, name, `order`, flags, content_type) VALUES (1, 'a', 1, 0, 0), (2, 'b', 2, 0, 0)",
        )

        @JvmStatic
        fun cases() = Type.entries.flatMap { type -> Case.entries.map { Arguments.of(type, it) } }
    }
}
