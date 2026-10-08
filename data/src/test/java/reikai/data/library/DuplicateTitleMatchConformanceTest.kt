package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
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
 * The add-to-library duplicate check matches by title the same way for both types: a library entry
 * whose title contains the one being added, in any case, other than the entry itself. The track match
 * beside it is DuplicateTrackMatchConformanceTest's.
 */
class DuplicateTitleMatchConformanceTest {

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
    fun `a library entry whose title contains the title in any case is a duplicate`(type: Type) = runTest {
        type.seed(driver)

        type.duplicatesOfFirst(database).map { it.first } shouldBe listOf(2L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a duplicate carries its chapter count`(type: Type) = runTest {
        type.seed(driver)

        type.duplicatesOfFirst(database).map { it.second } shouldBe listOf(2L)
    }

    /**
     * Entry 1 "Alpha" is being added; 2 "The ALPHA Saga" is in the library with two chapters, 3 "alpha"
     * is not in the library, and 4 "Beta" is in it under an unrelated title.
     */
    enum class Type(private val statements: List<String>) {
        MANGA(
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(1, 1, 'a', 'Alpha', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), " +
                    "(2, 1, 'b', 'The ALPHA Saga', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), " +
                    "(3, 1, 'c', 'alpha', 0, 0, 0, 0, 0, NULL, 0, 0, '', '{}'), " +
                    "(4, 1, 'd', 'Beta', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES " +
                    "(1, 2, 'c1', 'n', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}'), " +
                    "(2, 2, 'c2', 'n', NULL, 0, 0, 0, 2.0, 1, 0, 0, '{}')",
            ),
        ) {
            override suspend fun duplicatesOfFirst(database: Database) =
                MangaRepositoryImpl(database).getDuplicateLibraryManga(1L, "Alpha")
                    .map { it.manga.id to it.chapterCount }
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 'src', 'a', 'Alpha', 0, 0, 0, 0), (2, 'src', 'b', 'The ALPHA Saga', 0, 0, 0, 0), " +
                    "(3, 'src', 'c', 'alpha', 0, 0, 0, NULL), (4, 'src', 'd', 'Beta', 0, 0, 0, 0)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 2, 'c1', 'n', 0, 0, 0, 1.0, 0, 0, 0), (2, 2, 'c2', 'n', 0, 0, 0, 2.0, 1, 0, 0)",
            ),
        ) {
            override suspend fun duplicatesOfFirst(database: Database) =
                NovelRepositoryImpl(database).getDuplicateLibraryNovel(1L, "Alpha")
                    .map { it.novel.id to it.chapterCount }
        },
        ;

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        /** (entry id, chapter count) of each duplicate of entry 1. */
        abstract suspend fun duplicatesOfFirst(database: Database): List<Pair<Long, Long>>
    }
}
