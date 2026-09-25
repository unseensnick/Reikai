package reikai.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel
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
 * A history row cleared from History keeps its reading time, which the Stats total counts, so the
 * backup has to carry it too or a restore loses that time. Mihon's manga read writes every row.
 */
class NovelHistoryBackupTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        runTest {
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
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a history row cleared from History is backed up with its reading time`() = runTest {
        listOf(
            "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                "VALUES (1, 'src', 'novel', 'title', 0, 1, 0, 0)",
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, " +
                "source_order, date_fetch, date_upload) VALUES (10, 1, 'chapter', 'c', 1, 0, 1, 0, 0, 0)",
            "INSERT INTO novel_history(chapter_id, last_read, time_read) VALUES (10, 0, 500)",
        ).forEach { driver.execute(null, it, 0).await() }
        val backup = BackupNovel(source = "src", url = "novel")

        creator().history(Novel.create().copy(id = 1L), backup)

        backup.history.map { it.url to it.readDuration } shouldBe listOf("chapter" to 500L)
    }

    private fun creator() = NovelBackupCreator(
        novelRepository = mockk(),
        novelChapterRepository = mockk(),
        categoryRepository = mockk(),
        novelTrackRepository = mockk(),
        mergeGroupRepository = mockk(),
        customNovelInfoRepository = mockk(),
        database = database,
        novelSourceManager = mockk(),
    )
}
