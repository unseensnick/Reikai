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
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.domain.novel.model.Novel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.History

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
            database = DatabaseBindings.providesDatabase(driver)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a history row cleared from History is backed up with its reading time`() = runTest {
        listOf(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                "favorite_at) VALUES (1, 'src', 'novel', 'title', 0, 0, 0, 0)",
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
        novelChapterRepository = NovelChapterRepositoryImpl(database),
        categoryRepository = mockk(),
        novelTrackRepository = mockk(),
        mergeGroupRepository = mockk(),
        customNovelInfoRepository = mockk(),
        novelHistoryRepository = NovelHistoryRepositoryImpl(database),
        novelSourceManager = mockk(),
    )
}
