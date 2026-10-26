package reikai.data.recents

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.category.matchesCategoryFilter
import reikai.domain.category.orUncategorized
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import kotlin.time.Instant

/**
 * Every Recents feed filters categories in SQL, before its row limit, so the rule cannot run through the
 * Kotlin kernel. This binds each feed's copy to [matchesCategoryFilter] instead: every include and exclude
 * subset of {uncategorized, A, B} is put to all six feeds over entries on {}, {A}, {B} and {A, B}, and each
 * must keep exactly the entries the kernel keeps. Category id 0 is the uncategorized sentinel.
 */
class RecentsCategoryFilterConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            database = DatabaseBindings.providesDatabase(driver)
            CATEGORIES.forEach { driver.execute(null, it, 0).await() }
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest(name = "{0}: include {1}, exclude {2}")
    @MethodSource("cases")
    fun `a feed keeps the entries the category kernel keeps`(feed: Feed, include: Set<Long>, exclude: Set<Long>) =
        runTest {
            feed.seed(driver)

            val expected = MEMBERSHIPS
                .filter { (_, categories) -> matchesCategoryFilter(categories.orUncategorized(), include, exclude) }
                .map { (id, _) -> id }

            feed.ids(database, include.toList(), exclude.toList()) shouldContainExactlyInAnyOrder expected
        }

    // Guards the cases above against a seed the feed never returns, where every expected-empty case passes.
    @ParameterizedTest
    @EnumSource(Feed::class)
    fun `an unfiltered feed returns every seeded entry`(feed: Feed) = runTest {
        feed.seed(driver)

        feed.ids(database, emptyList(), emptyList()) shouldContainExactlyInAnyOrder MEMBERSHIPS.keys
    }

    /**
     * Each entry is favorited at 1 with one chapter fetched and uploaded at 1000 and read once, which puts it
     * in every feed: the updates feeds need a fetch after the add, recently-added a favorite date after [AFTER].
     */
    enum class Feed(private val isManga: Boolean) {
        MANGA_UPDATES(isManga = true) {
            override suspend fun ids(database: Database, include: List<Long>, exclude: List<Long>) =
                UpdatesRepositoryImpl(database).subscribeAll(
                    after = AFTER,
                    limit = 100,
                    unread = null,
                    started = null,
                    bookmarked = null,
                    hideExcludedScanlators = false,
                    includedCategories = include,
                    excludedCategories = exclude,
                ).first().map { it.mangaId }
        },
        NOVEL_UPDATES(isManga = false) {
            override suspend fun ids(database: Database, include: List<Long>, exclude: List<Long>) =
                NovelRepositoryImpl(database).getFilteredNovelUpdatesAsFlow(
                    after = AFTER,
                    limit = 100,
                    unread = null,
                    started = null,
                    bookmarked = null,
                    includedCategories = include,
                    excludedCategories = exclude,
                ).first().map { it.novelId }
        },
        MANGA_HISTORY(isManga = true) {
            override suspend fun ids(database: Database, include: List<Long>, exclude: List<Long>) =
                HistoryRepositoryImpl(database).getHistory("", include, exclude).first().map { it.mangaId }
        },
        NOVEL_HISTORY(isManga = false) {
            override suspend fun ids(database: Database, include: List<Long>, exclude: List<Long>) =
                NovelHistoryRepositoryImpl(database).getNovelHistory("", include, exclude).first().map { it.novelId }
        },
        MANGA_ADDED(isManga = true) {
            override suspend fun ids(database: Database, include: List<Long>, exclude: List<Long>) =
                RecentlyAddedRepositoryImpl(database).subscribeManga(AFTER, 100, include, exclude)
                    .first().map { it.mangaId }
        },
        NOVEL_ADDED(isManga = false) {
            override suspend fun ids(database: Database, include: List<Long>, exclude: List<Long>) =
                RecentlyAddedRepositoryImpl(database).subscribeNovels(AFTER, 100, include, exclude)
                    .first().map { it.novelId }
        },
        ;

        abstract suspend fun ids(database: Database, include: List<Long>, exclude: List<Long>): List<Long>

        suspend fun seed(driver: JdbcSqliteDriver) {
            (if (isManga) MANGA_SEED else NOVEL_SEED).forEach { driver.execute(null, it, 0).await() }
        }
    }

    companion object {
        private val AFTER = Instant.fromEpochMilliseconds(0)
        private const val A = 1L
        private const val B = 2L

        /** Entry id to the categories it sits on; both categories are universal (content type 0). */
        private val MEMBERSHIPS = mapOf(
            1L to emptyList(),
            2L to listOf(A),
            3L to listOf(B),
            4L to listOf(A, B),
        )

        private val CATEGORIES = listOf(
            "INSERT INTO category(id, name, `order`, flags, content_type) VALUES ($A, 'a', 1, 0, 0), ($B, 'b', 2, 0, 0)",
        )

        private val MANGA_SEED = MEMBERSHIPS.flatMap { (id, categories) ->
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, 1, " +
                    "'m-url-$id', 'title $id', 0, 0, 0, 0, 0, 1, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES ($id, " +
                    "$id, 'c-url-$id', 'name', NULL, 0, 0, 0, 1.0, 0, 1000, 1000, '{}')",
                "INSERT INTO history(id, chapter_id, read_at, read_duration, manga_id) " +
                    "VALUES ($id, $id, ${2000 - id}, 0, $id)",
            ) + categories.map { "INSERT INTO manga_category(manga_id, category_id) VALUES ($id, $it)" }
        }

        private val NOVEL_SEED = MEMBERSHIPS.flatMap { (id, categories) ->
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES ($id, 'src', 'n-url-$id', 'title $id', 0, 0, 0, 1)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) " +
                    "VALUES ($id, $id, 'c-url-$id', 'name', 0, 0, 0, 1.0, 0, 1000, 1000)",
                "INSERT INTO novel_history(_id, chapter_id, last_read, time_read) VALUES ($id, $id, ${2000 - id}, 0)",
            ) + categories.map { "INSERT INTO novels_categories(novel_id, category_id) VALUES ($id, $it)" }
        }

        private val SUBSETS: List<Set<Long>> = listOf(0L, A, B).let { ids ->
            (0 until 8).map { mask -> ids.filterIndexedTo(mutableSetOf()) { i, _ -> mask and (1 shl i) != 0 } }
        }

        @JvmStatic
        fun cases(): List<Arguments> = Feed.entries.flatMap { feed ->
            SUBSETS.flatMap { include -> SUBSETS.map { exclude -> Arguments.of(feed, include, exclude) } }
        }
    }
}
