package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.model.NovelUpdate
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * A batch update of entries or of chapters lands whole or not at all, for both types: migration's
 * favorite swap and its chapter carry depend on it. A trigger fails the second row's write, so the
 * first row shows whether the batch shared one transaction.
 */
class BatchUpdateAtomicityConformanceTest {

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
    fun `an entry batch whose second write fails leaves the first entry as it was`(type: Type) = runTest {
        type.seed(driver)
        driver.execute(null, type.failSecondEntry, 0).await()

        runCatching { type.noteBoth(database) }

        type.firstNote(database) shouldBe ""
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter batch whose second write fails leaves the first chapter as it was`(type: Type) = runTest {
        type.seed(driver)
        driver.execute(null, type.failSecondChapter, 0).await()

        runCatching { type.readBoth(database) }

        type.firstChapterRead(database) shouldBe false
    }

    /** Entries 1 and 2 with empty notes; entry 1 has unread chapters 1 and 2. */
    enum class Type(
        private val statements: List<String>,
        val failSecondEntry: String,
        val failSecondChapter: String,
    ) {
        MANGA(
            listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(1, 1, 'a', 't', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), " +
                    "(2, 1, 'b', 't', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES " +
                    "(1, 1, 'c1', 'n', NULL, 0, 0, 0, 1.0, 0, 0, 0, '{}'), " +
                    "(2, 1, 'c2', 'n', NULL, 0, 0, 0, 2.0, 1, 0, 0, '{}')",
            ),
            "CREATE TRIGGER fail BEFORE UPDATE ON manga WHEN NEW.id = 2 BEGIN SELECT RAISE(ABORT, 'boom'); END",
            "CREATE TRIGGER fail BEFORE UPDATE ON chapter WHEN NEW.id = 2 BEGIN SELECT RAISE(ABORT, 'boom'); END",
        ) {
            override suspend fun noteBoth(database: Database) {
                MangaRepositoryImpl(database).updateAll(listOf(1L, 2L).map { MangaUpdate(it) { notes = "n" } })
            }

            override suspend fun firstNote(database: Database) = MangaRepositoryImpl(database).getMangaById(1L).notes

            override suspend fun readBoth(database: Database) {
                ChapterRepositoryImpl(database).updateAll(listOf(1L, 2L).map { ChapterUpdate(it) { read = true } })
            }

            override suspend fun firstChapterRead(database: Database) =
                ChapterRepositoryImpl(database).getChapterById(1L)!!.read
        },
        NOVEL(
            listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 'src', 'a', 't', 0, 0, 0, 0), (2, 'src', 'b', 't', 0, 0, 0, 0)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 1, 'c1', 'n', 0, 0, 0, 1.0, 0, 0, 0), (2, 1, 'c2', 'n', 0, 0, 0, 2.0, 1, 0, 0)",
            ),
            "CREATE TRIGGER fail BEFORE UPDATE ON novels WHEN NEW._id = 2 BEGIN SELECT RAISE(ABORT, 'boom'); END",
            "CREATE TRIGGER fail BEFORE UPDATE ON novel_chapters WHEN NEW._id = 2 " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END",
        ) {
            override suspend fun noteBoth(database: Database) {
                NovelRepositoryImpl(database).updateAll(listOf(1L, 2L).map { NovelUpdate(it) { notes = "n" } })
            }

            override suspend fun firstNote(database: Database) = NovelRepositoryImpl(database).getById(1L)!!.notes

            override suspend fun readBoth(database: Database) {
                val chapters = NovelChapterRepositoryImpl(database)
                chapters.updateAll(listOf(1L, 2L).map { chapters.getById(it)!!.copy(read = true) })
            }

            override suspend fun firstChapterRead(database: Database) =
                NovelChapterRepositoryImpl(database).getById(1L)!!.read
        },
        ;

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        /** Writes a note on entries 1 and 2 in one batch. */
        abstract suspend fun noteBoth(database: Database)

        abstract suspend fun firstNote(database: Database): String

        /** Marks chapters 1 and 2 read in one batch. */
        abstract suspend fun readBoth(database: Database)

        abstract suspend fun firstChapterRead(database: Database): Boolean
    }
}
