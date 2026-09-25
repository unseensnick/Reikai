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
        database = Database(
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
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) VALUES (1, 1, 'm', 't', 0, 1, 0, 0, 0, 0, 0)",
                "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 1, 'a', 'n', NULL, 1, 0, 0, 1.0, 1, 0, 0), (2, 1, 'b', 'n', NULL, 1, 0, 0, 2.0, 0, 0, 0)",
                "INSERT INTO history(chapter_id, last_read, time_read) VALUES (1, 300, 0), (2, 500, 0)",
            ),
        ) {
            override suspend fun lastRead(database: Database) =
                database.libraryViewQueries.library(MangaMapper::mapLibraryManga).awaitAsList().single().lastRead

            override suspend fun clearHistory(database: Database) =
                HistoryRepositoryImpl(database).resetHistoryByMangaId(1L)
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags, " +
                    "date_added) VALUES (1, 'src', 'n', 't', 0, 1, 0, 0, 0)",
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
