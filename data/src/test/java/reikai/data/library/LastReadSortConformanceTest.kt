package reikai.data.library

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.mapLibraryNovel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaMapper

/**
 * The library's Last read sort is derived from reading history for both types, as Mihon's libraryView
 * does, so clearing an entry's history drops it in the sort instead of leaving a stored stamp behind.
 */
class LastReadSortConformanceTest {

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
    fun `Last read is the latest read in the entry's history`(type: Type) = runTest {
        type.seed(driver)

        type.lastRead(database) shouldBe 500L
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `clearing an entry's history drops its Last read`(type: Type) = runTest {
        type.seed(driver)

        type.clearHistory(database)

        type.lastRead(database) shouldBe 0L
    }

    /** One library entry with two chapters, read at 300 and 500. */
    enum class Type(private val statements: List<String>) {
        MANGA(
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES (1, 1, 'm', 't', " +
                    "0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES (1, 1, " +
                    "'a', 'n', NULL, 1, 0, 0, 1.0, 1, 0, 0, '{}'), (2, 1, 'b', 'n', NULL, 1, 0, 0, " +
                    "2.0, 0, 0, 0, '{}')",
                "INSERT INTO history(chapter_id, read_at, read_duration, manga_id) VALUES (1, 300, 0, " +
                    "(SELECT manga_id FROM chapter WHERE id = 1)), (2, 500, 0, " +
                    "(SELECT manga_id FROM chapter WHERE id = 2))",
            ),
        ) {
            override suspend fun lastRead(database: Database) =
                database.libraryViewQueries.library(MangaMapper::mapLibraryManga).awaitAsList().single().lastRead

            override suspend fun clearHistory(database: Database) =
                HistoryRepositoryImpl(database).resetHistoryByMangaId(1L)
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES (1, 'src', 'n', 't', 0, 0, 0, 0)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 1, 'a', 'n', 1, 0, 0, 1.0, 1, 0, 0), (2, 1, 'b', 'n', 1, 0, 0, 2.0, 0, 0, 0)",
                "INSERT INTO novel_history(chapter_id, last_read, time_read) VALUES (1, 300, 0), (2, 500, 0)",
            ),
        ) {
            override suspend fun lastRead(database: Database) =
                database.novelLibraryViewQueries.novelLibrary(::mapLibraryNovel).awaitAsList().single().lastRead

            override suspend fun clearHistory(database: Database) =
                NovelHistoryRepositoryImpl(database).resetNovelHistoryByNovelId(1L)
        },
        ;

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        abstract suspend fun lastRead(database: Database): Long

        abstract suspend fun clearHistory(database: Database)
    }
}
