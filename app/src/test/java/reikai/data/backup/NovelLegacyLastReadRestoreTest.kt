package reikai.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.mapLibraryNovel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * Backups made before the novel Last read sort came from history carry it as a stamp on the novel and
 * no history. Restoring one has to keep the novel's place in the sort, and a backup that does carry
 * history keeps what its history says.
 */
class NovelLegacyLastReadRestoreTest {

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

    @Test
    fun `an old backup's Last read stamp keeps the novel's place in the sort`() = runTest {
        restorer().restore(backup(history = emptyList()), emptyList())

        lastRead() shouldBe 500L
    }

    @Test
    fun `a backup with history keeps its history's Last read`() = runTest {
        restorer().restore(backup(history = listOf(BackupNovelHistory(url = "c1", lastRead = 300L))), emptyList())

        lastRead() shouldBe 300L
    }

    private fun backup(history: List<BackupNovelHistory>) = BackupNovel(
        source = "src",
        url = "novel",
        lastReadAt = 500L,
        chapters = listOf(
            BackupNovelChapter(url = "c1", name = "1", read = true, chapterNumber = 1.0),
            BackupNovelChapter(url = "c2", name = "2", chapterNumber = 2.0),
        ),
        history = history,
    )

    private suspend fun lastRead() =
        database.novelLibraryViewQueries.novelLibrary(::mapLibraryNovel).awaitAsList().single().lastRead

    private fun restorer() = NovelRestorer(
        novelRepository = NovelRepositoryImpl(database),
        novelChapterRepository = NovelChapterRepositoryImpl(database),
        categoryRepository = mockk(),
        novelTrackRepository = mockk(),
        restoreMergeGroups = mockk(),
        setCustomNovelInfo = mockk(),
        novelHistoryRepository = NovelHistoryRepositoryImpl(database),
    )
}
