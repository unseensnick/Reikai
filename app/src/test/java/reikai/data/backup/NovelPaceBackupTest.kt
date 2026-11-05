package reikai.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
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
import reikai.data.readinglog.ReadingPaceRepositoryImpl
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.readinglog.NoReadingLog
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * A novel's pace is learned from each chapter's word count and the part of its history time read aloud,
 * so a backup carries both, as manga's carries the page count its pace is learned from.
 */
class NovelPaceBackupTest {

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
    fun `a backup carries each chapter's word count`() = runTest {
        storeChapterWithPace()
        val backup = BackupNovel(source = "src", url = "novel")

        creator().chapters(listOf(Novel.create().copy(id = 1L) to backup))

        backup.chapters.map { it.url to it.words } shouldBe listOf("chapter" to 2_000L)
    }

    @Test
    fun `a backup carries each chapter's read-aloud time with its history`() = runTest {
        storeChapterWithPace()
        val backup = BackupNovel(source = "src", url = "novel")

        creator().history(listOf(Novel.create().copy(id = 1L) to backup))

        backup.history.map { it.url to it.listenedMs } shouldBe listOf("chapter" to 400L)
    }

    @Test
    fun `a restore puts back the word count and read-aloud time`() = runTest {
        restorer().restore(backup(), emptyList())

        pace() shouldBe listOf("chapter" to (2_000L to 400L))
    }

    @Test
    fun `restoring the same backup twice adds no read-aloud time`() = runTest {
        restorer().restore(backup(), emptyList())
        restorer().restore(backup(), emptyList())

        pace() shouldBe listOf("chapter" to (2_000L to 400L))
    }

    private suspend fun storeChapterWithPace() {
        listOf(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                "favorite_at) VALUES (1, 'src', 'novel', 'title', 0, 0, 0, 0)",
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, " +
                "source_order, date_fetch, date_upload) VALUES (10, 1, 'chapter', 'c', 1, 0, 1, 0, 0, 0)",
            "INSERT INTO novel_history(chapter_id, last_read, time_read) VALUES (10, 300, 500)",
            "INSERT INTO novel_chapter_words(chapter_id, words, listened_ms) VALUES (10, 2000, 400)",
        ).forEach { driver.execute(null, it, 0).await() }
    }

    private fun backup() = BackupNovel(
        source = "src",
        url = "novel",
        chapters = listOf(BackupNovelChapter(url = "chapter", name = "c", read = true, words = 2_000)),
        history = listOf(BackupNovelHistory(url = "chapter", lastRead = 300L, readDuration = 500L, listenedMs = 400L)),
    )

    private suspend fun pace() = ReadingPaceRepositoryImpl(database).novelChapterPace(listOf(1L))
        .map { it.url to (it.words to it.listenedMs) }

    private fun creator() = NovelBackupCreator(
        novelRepository = mockk(),
        novelChapterRepository = NovelChapterRepositoryImpl(database),
        categoryRepository = mockk(),
        novelTrackRepository = mockk(),
        mergeGroupRepository = mockk(),
        customNovelInfoRepository = mockk(),
        novelHistoryRepository = NovelHistoryRepositoryImpl(database),
        novelSourceManager = mockk(),
        chapterNumberOverrides = NoChapterNumberOverrides,
        readingLog = NoReadingLog,
        readingPace = ReadingPaceRepositoryImpl(database),
        localNovelLanguages = NoLocalNovelLanguages,
    )

    private fun restorer() = NovelRestorer(
        novelRepository = NovelRepositoryImpl(database),
        novelChapterRepository = NovelChapterRepositoryImpl(database),
        categoryRepository = mockk(),
        novelTrackRepository = mockk(),
        restoreMergeGroups = mockk(),
        setCustomNovelInfo = mockk(),
        novelHistoryRepository = NovelHistoryRepositoryImpl(database),
        chapterNumberOverrides = NoChapterNumberOverrides,
        readingLog = NoReadingLog,
        readingPace = ReadingPaceRepositoryImpl(database),
        localNovelLanguages = NoLocalNovelLanguages,
    )
}
