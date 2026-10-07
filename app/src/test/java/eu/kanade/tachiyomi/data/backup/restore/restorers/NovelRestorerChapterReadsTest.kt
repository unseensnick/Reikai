package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * A novel's restore reads its chapter list once and works from that, the chapters it inserts included,
 * for the update prediction and for placing each history row. Runs the real restorer over real SQL.
 */
class NovelRestorerChapterReadsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var chapters: NovelChapterRepositoryImpl

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        chapters = spyk(NovelChapterRepositoryImpl(database))
        listOf(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                "favorite_at) VALUES (1, 'src', 'u', 'T', 0, 0, 0, 0)",
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                "chapter_number, source_order, date_fetch, date_upload) VALUES (1, 1, 'c1', 'C1', 1, 0, 0, 1.0, " +
                "3, 0, ${day(11)})",
        ).forEach { driver.execute(null, it, 0).await() }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `the chapter list is read once`() = runTest {
        restore()

        coVerify(exactly = 1) { chapters.getByNovelId(1L) }
        coVerify(exactly = 0) { chapters.getByUrlAndNovelId(any(), any()) }
    }

    @Test
    fun `history lands on the chapters already stored and on the ones the restore inserts`() = runTest {
        restore()

        val urlById = NovelChapterRepositoryImpl(database).getByNovelId(1L).associate { it.id to it.url }
        NovelHistoryRepositoryImpl(database).getHistoryByNovelId(1L)
            .map { Triple(urlById[it.chapterId], it.readAt, it.readDuration) }
            .sortedBy { it.first } shouldBe listOf(Triple("c1", 100L, 5L), Triple("c4", 200L, 7L))
    }

    @Test
    fun `the update prediction counts the chapters the restore inserts`() = runTest {
        restore()

        // Four chapters two days apart; the one already stored alone would predict the default week.
        NovelRepositoryImpl(database).getById(1L)?.fetchInterval shouldBe 2
    }

    private suspend fun restore() = NovelRestorer(
        novelRepository = NovelRepositoryImpl(database),
        novelChapterRepository = chapters,
        categoryRepository = mockk(relaxed = true),
        novelTrackRepository = mockk(relaxed = true),
        restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
        setCustomNovelInfo = mockk(relaxed = true),
        novelHistoryRepository = NovelHistoryRepositoryImpl(database),
        chapterNumberOverrides = NoChapterNumberOverrides,
    ).restore(
        BackupNovel(
            source = "src",
            url = "u",
            title = "T",
            chapters = listOf(
                BackupNovelChapter(url = "c1", name = "C1", chapterNumber = 1.0, sourceOrder = 3, dateUpload = day(11)),
                BackupNovelChapter(url = "c2", name = "C2", chapterNumber = 2.0, sourceOrder = 2, dateUpload = day(13)),
                BackupNovelChapter(url = "c3", name = "C3", chapterNumber = 3.0, sourceOrder = 1, dateUpload = day(15)),
                BackupNovelChapter(url = "c4", name = "C4", chapterNumber = 4.0, sourceOrder = 0, dateUpload = day(17)),
            ),
            history = listOf(
                BackupNovelHistory(url = "c1", lastRead = 100L, readDuration = 5L),
                BackupNovelHistory(url = "c4", lastRead = 200L, readDuration = 7L),
            ),
        ),
        emptyList(),
    )

    // Noon UTC, so the two-day gaps hold in whatever zone the prediction reads them in.
    private fun day(day: Int) = (day - 1) * 86_400_000L + 12 * 3_600_000L + JAN_1_2026
}

private const val JAN_1_2026 = 1_767_225_600_000L
