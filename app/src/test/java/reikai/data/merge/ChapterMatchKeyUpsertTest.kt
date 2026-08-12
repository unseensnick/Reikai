package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterMatchKeyRepository.ResolvedKey
import reikai.domain.merge.ChapterMatchKeyRepository.ResolvedNovelKey
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

/**
 * A cross-source chapter identity may only be written for a chapter that still exists. Reconciliation
 * reads the stale chapters and writes their keys in a later transaction, so a chapter deleted in
 * between (a sync re-adding it under a new row id is the common case) would otherwise leave a key on
 * a dead id. That is fatal rather than wasteful: the driver verifies foreign keys after applying
 * migrations, so one orphan makes the next migration crash on startup. Both types write keys through
 * the same reconciliation, so both are checked.
 */
class ChapterMatchKeyUpsertTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: ChapterMatchKeyRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
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
            repository = ChapterMatchKeyRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a manga key is written for a chapter that exists`() = runTest {
        seedManga(id = 1)

        repository.upsertMangaKeys(listOf(ResolvedKey(chapterId = 1, matchKey = "k", chapterNumber = 1.0)))

        mangaKeyCount() shouldBe 1
    }

    @Test
    fun `a manga key is not written for a chapter that has been deleted`() = runTest {
        repository.upsertMangaKeys(listOf(ResolvedKey(chapterId = 404, matchKey = "k", chapterNumber = 1.0)))

        mangaKeyCount() shouldBe 0
    }

    @Test
    fun `a novel key is written for a chapter that exists`() = runTest {
        seedNovel(id = 1)

        repository.upsertNovelKeys(
            listOf(ResolvedNovelKey(chapterId = 1, matchKey = "k", name = "n", chapterNumber = 1.0)),
        )

        novelKeyCount() shouldBe 1
    }

    @Test
    fun `a novel key is not written for a chapter that has been deleted`() = runTest {
        repository.upsertNovelKeys(
            listOf(ResolvedNovelKey(chapterId = 404, matchKey = "k", name = "n", chapterNumber = 1.0)),
        )

        novelKeyCount() shouldBe 0
    }

    /** The whole batch must not be lost because one of its chapters went away mid-reconciliation. */
    @Test
    fun `a live chapter in the same batch as a deleted one still gets its key`() = runTest {
        seedNovel(id = 1)

        repository.upsertNovelKeys(
            listOf(
                ResolvedNovelKey(chapterId = 404, matchKey = "gone", name = "n", chapterNumber = 1.0),
                ResolvedNovelKey(chapterId = 1, matchKey = "live", name = "n", chapterNumber = 1.0),
            ),
        )

        novelKeyCount() shouldBe 1
    }

    private suspend fun mangaKeyCount(): Long = count("chapter_match_key")

    private suspend fun novelKeyCount(): Long = count("novel_chapter_match_key")

    private suspend fun count(table: String): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM $table",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value.let { cursor.getLong(0)!! })
        },
        parameters = 0,
    ).await()

    private suspend fun seedManga(id: Long) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) " +
                "VALUES ($id, 1, 'm-url-$id', 'title $id', 0, 1, 0, 0, 0, 0, 0)",
            0,
        ).await()
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, " +
                "last_page_read, chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES ($id, $id, 'c-url-$id', 'name', NULL, 0, 0, 0, 1.0, 0, 1000, 1000)",
            0,
        ).await()
    }

    private suspend fun seedNovel(id: Long) {
        driver.execute(
            null,
            "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags, " +
                "date_added) VALUES ($id, 'src', 'n-url-$id', 'title $id', 0, 1, 0, 0, 0)",
            0,
        ).await()
        driver.execute(
            null,
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                "chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES ($id, $id, 'c-url-$id', 'name', 0, 0, 0, 1.0, 0, 1000, 1000)",
            0,
        ).await()
    }
}
