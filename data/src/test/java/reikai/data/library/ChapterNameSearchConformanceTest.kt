package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.ChapterNameFoldBackfill
import reikai.data.novel.NovelChapterRepositoryImpl
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate

/** The library's `chapter:` term finds a chapter name containing the term in any case, for both types. */
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

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an ASCII name is found in another case without a stored fold`(type: Type) = runTest {
        type.seed(driver)

        type.matching(database, "START") shouldBe setOf(1L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a term in capitals finds a lower-case name`(type: Type) = runTest {
        type.seed(driver)
        type.rename(driver, "глава 1")

        type.matching(database, "ГЛАВА") shouldBe setOf(1L)
    }

    /** Every write that stores a chapter name stores its fold, so a capitalised Cyrillic name is found in lower case. */
    @ParameterizedTest
    @EnumSource(Write::class)
    fun `a name is found in another case after each write that stores it`(write: Write) = runTest {
        write.type.seed(driver)

        write.run(database)

        write.type.matching(database, "глава") shouldBe setOf(1L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a name stored before its fold is found once the backfill has run`(type: Type) = runTest {
        type.seed(driver)
        type.rename(driver, "Глава 1")

        ChapterNameFoldBackfill(database).run()

        type.matching(database, "глава") shouldBe setOf(1L)
    }

    enum class Write(val type: Type) {
        MANGA_ADD(Type.MANGA) {
            override suspend fun run(database: Database) {
                ChapterRepositoryImpl(database).updateFromRemote(
                    removedIds = emptyList(),
                    added = listOf(Chapter.create().copy(mangaId = 1L, url = "new", name = "Глава 1")),
                    updated = emptyList(),
                )
            }
        },
        MANGA_RENAME(Type.MANGA) {
            override suspend fun run(database: Database) {
                ChapterRepositoryImpl(database).updateFromRemote(
                    removedIds = emptyList(),
                    added = emptyList(),
                    updated = listOf(
                        ChapterRemoteUpdate(1L, "Глава 1", null, 1.0, null, 0L, JsonObject(emptyMap())),
                    ),
                )
            }
        },
        NOVEL_INSERT(Type.NOVEL) {
            override suspend fun run(database: Database) {
                val repository = NovelChapterRepositoryImpl(database)
                repository.insert(repository.getById(1L)!!.copy(url = "new", name = "Глава 1"))
            }
        },
        NOVEL_SYNC_RENAME(Type.NOVEL) {
            override suspend fun run(database: Database) {
                val repository = NovelChapterRepositoryImpl(database)
                val renamed = repository.getById(1L)!!.copy(name = "Глава 1")
                repository.updateFromRemote(removedIds = emptyList(), added = emptyList(), updated = listOf(renamed))
            }
        },
        NOVEL_UPDATE(Type.NOVEL) {
            override suspend fun run(database: Database) {
                val repository = NovelChapterRepositoryImpl(database)
                repository.update(repository.getById(1L)!!.copy(name = "Глава 1"))
                // A write naming no chapter name keeps the fold
                repository.setBookmarkBulk(listOf(1L), true)
            }
        },
        ;

        abstract suspend fun run(database: Database)
    }

    enum class Type(private val statements: List<String>, private val renameFirst: String) {
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
            "UPDATE chapter SET remote_name = ? WHERE id = 1",
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
            "UPDATE novel_chapters SET name = ? WHERE _id = 1",
        ) {
            override suspend fun matching(database: Database, term: String) =
                NovelChapterRepositoryImpl(database).getNovelIdsWithChapterNameLike(term)
        },
        ;

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        /** Renames the first entry's chapter in SQL, leaving its fold as a row stored before the column has it. */
        suspend fun rename(driver: JdbcSqliteDriver, name: String) =
            driver.execute(null, renameFirst, 1) { bindString(0, name) }.await()

        abstract suspend fun matching(database: Database, term: String): Set<Long>
    }
}
