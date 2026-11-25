package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl

/** The library's `chapter:` term finds a chapter name containing the term as typed, for both types. */
class ChapterNameSearchConformanceTest {

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

    /** Entry 1's chapter is "V.1 start 10", entry 2's "v_1 extra" and entry 3's "100% done". */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an underscore in the term matches only an underscore`(type: Type) = runTest {
        type.seed(driver)

        type.matching(database, "v_1") shouldBe setOf(2L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a percent sign in the term matches only a percent sign`(type: Type) = runTest {
        type.seed(driver)

        type.matching(database, "0%") shouldBe setOf(3L)
    }

    enum class Type(private val statements: List<String>) {
        MANGA(
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(1, 1, 'a', 'A', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), " +
                    "(2, 1, 'b', 'B', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), " +
                    "(3, 1, 'c', 'C', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES " +
                    "(1, 1, 'c1', 'V.1 start 10', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}'), " +
                    "(2, 2, 'c2', 'v_1 extra', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}'), " +
                    "(3, 3, 'c3', '100% done', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}')",
            ),
        ) {
            override suspend fun matching(database: Database, term: String) =
                ChapterRepositoryImpl(database).getMangaIdsWithChapterNameLike(term)
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 'src', 'a', 'A', 0, 0, 0, 0), (2, 'src', 'b', 'B', 0, 0, 0, 0), " +
                    "(3, 'src', 'c', 'C', 0, 0, 0, 0)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 1, 'c1', 'V.1 start 10', 0, 0, 0, 1.0, 0, 0, 0), " +
                    "(2, 2, 'c2', 'v_1 extra', 0, 0, 0, 1.0, 0, 0, 0), " +
                    "(3, 3, 'c3', '100% done', 0, 0, 0, 1.0, 0, 0, 0)",
            ),
        ) {
            override suspend fun matching(database: Database, term: String) =
                NovelChapterRepositoryImpl(database).getNovelIdsWithChapterNameLike(term)
        },
        ;

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        abstract suspend fun matching(database: Database, term: String): Set<Long>
    }
}
