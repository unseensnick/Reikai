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

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a series hidden from the library is left out of the feed`(type: Type) = runTest {
        type.seed(driver, type.statements + type.hideEntryThree)

        type.chapterIds(database, Case.NONE) shouldBe listOf(1L, 2L, 3L, 6L)
    }

    /** Entries 3 and 4 are one merged series and only entry 3 is hidden, so the library hides both while merging is on. */
    @ParameterizedTest
    @MethodSource("mergedHideCases")
    fun `a merged series with a hidden member follows the library's hiding`(
        type: Type,
        seriesMerging: Boolean,
        expected: List<Long>,
    ) = runTest {
        type.seed(driver, type.statements + type.hideEntryThree + type.groupThreeAndFour)

        type.chapterIds(database, Case.NONE, seriesMerging) shouldBe expected
    }

    /** The widgets' unread feed: chapters 2, 3, 4 and 6 are unread, 4 of entry 3 and 6 of entry 4. */
    @ParameterizedTest
    @MethodSource("mergedHideWidgetCases")
    fun `the widgets' unread feed follows the library's hiding of a merged series`(
        type: Type,
        seriesMerging: Boolean,
        expected: List<Long>,
    ) = runTest {
        type.seed(driver, type.statements + type.hideEntryThree + type.groupThreeAndFour)

        type.readStateFeed(database, read = false, seriesMerging).sorted() shouldBe expected
    }

    /** The read feed (getReadUpdates on manga): chapter 12 of entry 4 is read, as are 1 (entry 1) and 5 (entry 3). */
    @ParameterizedTest
    @MethodSource("mergedHideReadCases")
    fun `the read feed follows the library's hiding of a merged series`(
        type: Type,
        seriesMerging: Boolean,
        expected: List<Long>,
    ) = runTest {
        type.seed(driver, type.statements + type.hideEntryThree + type.groupThreeAndFour + type.readOnEntryFour)

        type.readStateFeed(database, read = true, seriesMerging).sorted() shouldBe expected
    }

    /** Entry 5 was added at 100 and its chapter 8 fetched and uploaded at 300, both before the cutoff. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter from before the cutoff is left out`(type: Type) = runTest {
        type.seed(driver, type.statements + type.beforeCutoff)

        type.chapterIds(database, Case.NONE) shouldBe Case.NONE.expected
    }

    /** Chapter 9 of entry 1 (added at 1000) was fetched and uploaded at 900, after the cutoff. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter fetched before its series was added is left out`(type: Type) = runTest {
        type.seed(driver, type.statements + type.fetchedBeforeAdded)

        type.chapterIds(database, Case.NONE) shouldBe Case.NONE.expected
    }

    /** Chapters 10 and 11 were fetched at 3000 and 4000, after every other chapter's 2000. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the feed is the newest fetches first, cut at the limit`(type: Type) = runTest {
        type.seed(driver, type.statements + type.newest)

        type.query(database, Case.NONE, limit = 2) shouldBe listOf(11L, 10L)
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
        val hideEntryThree: String,
        val groupThreeAndFour: List<String>,
        val beforeCutoff: List<String>,
        val fetchedBeforeAdded: String,
        val newest: String,
        val readOnEntryFour: String,
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
            hideEntryThree = "INSERT INTO library_hidden_manga(manga_id) VALUES (3)",
            groupThreeAndFour = listOf(
                "INSERT INTO merge_group(_id, content_type) VALUES (1, 0)",
                "INSERT INTO merge_group_manga(group_id, manga_id) VALUES (1, 3), (1, 4)",
            ),
            beforeCutoff = listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(5, 1, 'e', 't', 0, 0, 0, 0, 0, 100, 0, 0, '', '{}')",
                mangaChapter(id = 8, mangaId = 5, at = 300),
            ),
            fetchedBeforeAdded = mangaChapter(id = 9, mangaId = 1, at = 900),
            newest = mangaChapter(id = 10, mangaId = 1, at = 3000) + ", " + mangaChapterValues(11, 1, 4000),
            readOnEntryFour =
            "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                "remote_date_upload, remote_memo) VALUES (12, 4, 'c12', 'n', NULL, 1, 0, 0, 2.0, 1, 2000, 2000, '{}')",
        ) {
            // The excluded-scanlator filter is manga only, so it stays off to compare like with like.
            override suspend fun query(database: Database, case: Case, limit: Long, seriesMerging: Boolean) =
                UpdatesRepositoryImpl(database)
                    .subscribeAll(
                        after = AFTER,
                        limit = limit,
                        unread = case.unread,
                        started = case.started,
                        bookmarked = case.bookmarked,
                        hideExcludedScanlators = false,
                        includedCategories = case.included,
                        excludedCategories = emptyList(),
                        seriesMerging = seriesMerging,
                    )
                    .first()
                    .map { it.chapterId }

            override suspend fun readStateFeed(database: Database, read: Boolean, seriesMerging: Boolean) =
                UpdatesRepositoryImpl(database)
                    .subscribeWithRead(
                        read = read,
                        after = AFTER,
                        limit = Long.MAX_VALUE,
                        seriesMerging = seriesMerging,
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
            hideEntryThree = "INSERT INTO library_hidden_novel(novel_id) VALUES (3)",
            groupThreeAndFour = listOf(
                "INSERT INTO merge_group(_id, content_type) VALUES (1, 1)",
                "INSERT INTO merge_group_novel(group_id, novel_id) VALUES (1, 3), (1, 4)",
            ),
            beforeCutoff = listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (5, 's1', 'e', 't', 0, 0, 0, 100)",
                novelChapter(id = 8, novelId = 5, at = 300),
            ),
            fetchedBeforeAdded = novelChapter(id = 9, novelId = 1, at = 900),
            newest = novelChapter(id = 10, novelId = 1, at = 3000) + ", " + novelChapterValues(11, 1, 4000),
            readOnEntryFour =
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                "chapter_number, source_order, date_fetch, date_upload) VALUES (12, 4, 'c12', 'n', 1, 0, 0, 2.0, 1, 2000, 2000)",
        ) {
            override suspend fun query(database: Database, case: Case, limit: Long, seriesMerging: Boolean) =
                NovelRepositoryImpl(database)
                    .getFilteredNovelUpdatesAsFlow(
                        after = AFTER,
                        limit = limit,
                        unread = case.unread,
                        started = case.started,
                        bookmarked = case.bookmarked,
                        includedCategories = case.included,
                        excludedCategories = emptyList(),
                        seriesMerging = seriesMerging,
                    )
                    .first()
                    .map { it.chapterId }

            override suspend fun readStateFeed(database: Database, read: Boolean, seriesMerging: Boolean) =
                query(database, if (read) Case.READ else Case.UNREAD, Long.MAX_VALUE, seriesMerging)
        },
        ;

        /** In the order the feed returns them. */
        abstract suspend fun query(
            database: Database,
            case: Case,
            limit: Long = Long.MAX_VALUE,
            seriesMerging: Boolean = true,
        ): List<Long>

        /** The feed by read state: Mihon's read-state queries on manga (the widgets read unread, unifiedWidgetUpdates),
         *  the read filter on novels. */
        abstract suspend fun readStateFeed(database: Database, read: Boolean, seriesMerging: Boolean): List<Long>

        suspend fun chapterIds(database: Database, case: Case, seriesMerging: Boolean = true) =
            query(database, case, seriesMerging = seriesMerging).sorted()

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

        @JvmStatic
        fun mergedHideCases() = Type.entries.flatMap {
            listOf(Arguments.of(it, true, listOf(1L, 2L, 3L)), Arguments.of(it, false, listOf(1L, 2L, 3L, 6L)))
        }

        @JvmStatic
        fun mergedHideWidgetCases() = Type.entries.flatMap {
            listOf(Arguments.of(it, true, listOf(2L, 3L)), Arguments.of(it, false, listOf(2L, 3L, 6L)))
        }

        @JvmStatic
        fun mergedHideReadCases() = Type.entries.flatMap {
            listOf(Arguments.of(it, true, listOf(1L)), Arguments.of(it, false, listOf(1L, 12L)))
        }
    }
}

/** One unread chapter fetched and uploaded at [at]. */
private fun mangaChapter(id: Long, mangaId: Long, at: Long) =
    "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
        "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
        "remote_date_upload, remote_memo) VALUES " + mangaChapterValues(id, mangaId, at)

private fun mangaChapterValues(id: Long, mangaId: Long, at: Long) =
    "($id, $mangaId, 'c$id', 'n', NULL, 0, 0, 0, $id.0, $id, $at, $at, '{}')"

private fun novelChapter(id: Long, novelId: Long, at: Long) =
    "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
        "chapter_number, source_order, date_fetch, date_upload) VALUES " + novelChapterValues(id, novelId, at)

private fun novelChapterValues(id: Long, novelId: Long, at: Long) =
    "($id, $novelId, 'c$id', 'n', 0, 0, 0, $id.0, $id, $at, $at)"
