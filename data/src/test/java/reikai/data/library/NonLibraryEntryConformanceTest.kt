package reikai.data.library

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelRepositoryImpl
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl

/**
 * Entries outside the library answer alike for both types: a read chapter or a mid-chapter position is
 * progress, which the read-entries backup option keeps and Clear database can spare, and each source
 * counts what it holds outside the library. The manga half is Mihon's, the novel half Reikai's.
 */
class NonLibraryEntryConformanceTest {

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
    @EnumSource(Type::class)
    fun `an entry outside the library with a read chapter or a position is a read entry`(type: Type) = runTest {
        type.seed(driver)

        type.readNotInLibrary(database) shouldBe listOf(2L, 3L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `clearing while keeping read entries deletes only the untouched ones of the chosen source`(type: Type) =
        runTest {
            type.seed(driver)

            type.clear(database, keepRead = true)

            type.remaining(driver) shouldBe listOf(1L, 2L, 3L, 5L)
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `clearing without keeping read entries deletes every one outside the library`(type: Type) = runTest {
        type.seed(driver)

        type.clear(database, keepRead = false)

        type.remaining(driver) shouldBe listOf(1L, 5L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `each source counts its entries outside the library`(type: Type) = runTest {
        type.seed(driver)

        type.nonLibraryCounts(database) shouldBe mapOf(type.firstSource to 3L, type.secondSource to 1L)
    }

    /**
     * On the first source: entry 1 in the library with a read chapter, then three outside it, 2 with a
     * read chapter, 3 with a position in an unread one, 4 untouched. Entry 5, outside it, is the second
     * source's.
     */
    enum class Type(val firstSource: String, val secondSource: String, private val statements: List<String>) {
        MANGA(
            "1",
            "2",
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(1, 1, 'a', 't', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), " +
                    "(2, 1, 'b', 't', 0, 0, 0, 0, 0, NULL, 0, 0, '', '{}'), " +
                    "(3, 1, 'c', 't', 0, 0, 0, 0, 0, NULL, 0, 0, '', '{}'), " +
                    "(4, 1, 'd', 't', 0, 0, 0, 0, 0, NULL, 0, 0, '', '{}'), " +
                    "(5, 2, 'e', 't', 0, 0, 0, 0, 0, NULL, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES " +
                    "(1, 1, 'c1', 'n', NULL, 1, 0, 0, 1.0, 0, 0, 0, '{}'), " +
                    "(2, 2, 'c2', 'n', NULL, 1, 0, 0, 1.0, 0, 0, 0, '{}'), " +
                    "(3, 3, 'c3', 'n', NULL, 0, 0, 5, 1.0, 0, 0, 0, '{}'), " +
                    "(4, 4, 'c4', 'n', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}')",
            ),
        ) {
            override suspend fun readNotInLibrary(database: Database) =
                MangaRepositoryImpl(database).getReadMangaNotInLibrary().map { it.id }.sorted()

            override suspend fun clear(database: Database, keepRead: Boolean) =
                MangaRepositoryImpl(database).deleteNonLibraryManga(listOf(1L), keepRead)

            override suspend fun remaining(driver: JdbcSqliteDriver) = ids(driver, "SELECT id FROM manga ORDER BY id")

            // The repository adds only the source manager's display data on top of this query.
            override suspend fun nonLibraryCounts(database: Database) =
                database.mangaQueries.getSourceIdsWithNonLibraryManga { source, count -> source.toString() to count }
                    .awaitAsList().toMap()
        },
        NOVEL(
            "s1",
            "s2",
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 's1', 'a', 't', 0, 0, 0, 0), (2, 's1', 'b', 't', 0, 0, 0, NULL), " +
                    "(3, 's1', 'c', 't', 0, 0, 0, NULL), (4, 's1', 'd', 't', 0, 0, 0, NULL), " +
                    "(5, 's2', 'e', 't', 0, 0, 0, NULL)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 1, 'c1', 'n', 1, 0, 0, 1.0, 0, 0, 0), (2, 2, 'c2', 'n', 1, 0, 0, 1.0, 0, 0, 0), " +
                    "(3, 3, 'c3', 'n', 0, 0, 5, 1.0, 0, 0, 0), (4, 4, 'c4', 'n', 0, 0, 0, 1.0, 0, 0, 0)",
            ),
        ) {
            override suspend fun readNotInLibrary(database: Database) =
                NovelRepositoryImpl(database).getReadNovelsNotInLibrary().map { it.id }.sorted()

            override suspend fun clear(database: Database, keepRead: Boolean) =
                NovelRepositoryImpl(database).deleteNonLibraryNovels(listOf("s1"), keepRead)

            override suspend fun remaining(driver: JdbcSqliteDriver) =
                ids(driver, "SELECT _id FROM novels ORDER BY _id")

            override suspend fun nonLibraryCounts(database: Database) =
                NovelRepositoryImpl(database).getSourcesWithNonLibraryNovelAsFlow().first().toMap()
        },
        ;

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        abstract suspend fun readNotInLibrary(database: Database): List<Long>

        /** Clears the first source. */
        abstract suspend fun clear(database: Database, keepRead: Boolean)

        abstract suspend fun remaining(driver: JdbcSqliteDriver): List<Long>

        abstract suspend fun nonLibraryCounts(database: Database): Map<String, Long>

        protected suspend fun ids(driver: JdbcSqliteDriver, sql: String): List<Long> = driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getLong(0)!!) }) },
            0,
        ).await()
    }
}
