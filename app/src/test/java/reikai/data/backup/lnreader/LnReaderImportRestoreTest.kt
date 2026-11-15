package reikai.data.backup.lnreader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** An imported LNReader novel restored through the real restorer over real SQL. */
class LnReaderImportRestoreTest {

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
    fun `an imported library lists nothing in Updates`() = runTest {
        val novel = LnReaderNovel(
            id = 1,
            path = "novel/a",
            pluginId = "novelbin",
            name = "A",
            inLibrary = true,
            chapters = listOf(
                LnReaderChapter(path = "c1", name = "Chapter 1"),
                LnReaderChapter(path = "c2", name = "Chapter 2", position = 1),
            ),
        )

        restorer().restore(novel.toImportedNovel(emptyList(), TimeZone.UTC), emptyList())

        updates() shouldBe 0L
    }

    @Test
    fun `an imported novel is stored with its chapters and their read state`() = runTest {
        val novel = LnReaderNovel(
            id = 1,
            path = "novel/a",
            pluginId = "novelbin",
            name = "A",
            inLibrary = true,
            chapters = listOf(
                LnReaderChapter(path = "c1", name = "Chapter 1", unread = false),
                LnReaderChapter(path = "c2", name = "Chapter 2", position = 1),
            ),
        )

        restorer().restore(novel.toImportedNovel(emptyList(), TimeZone.UTC), emptyList())

        val stored = NovelRepositoryImpl(database).getByUrlAndSource("novel/a", "novelbin")!!
        NovelChapterRepositoryImpl(database).getByNovelId(stored.id).sortedBy {
            it.url
        }.map { it.url to it.read } shouldBe
            listOf("c1" to true, "c2" to false)
    }

    private fun updates(): Long = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM novelUpdatesView",
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        0,
    ).value ?: 0L

    private fun restorer() = NovelRestorer(
        tombstones = NoChapterTombstoneRepository,
        manualOrder = FakeLibraryManualOrderRepository(),
        novelRepository = NovelRepositoryImpl(database),
        novelChapterRepository = NovelChapterRepositoryImpl(database),
        categoryRepository = mockk(relaxed = true),
        novelTrackRepository = mockk(relaxed = true),
        restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
        setCustomNovelInfo = mockk(relaxed = true),
        novelHistoryRepository = NovelHistoryRepositoryImpl(database),
        chapterNumberOverrides = NoChapterNumberOverrides,
        copyMarks = InMemoryChapterCopyMarks(),
        localVolumes = NoLocalVolumes,
        volumePicks = NoVolumePicks,
        readingLog = NoReadingLog,
        readingPace = NoReadingPace,
        localNovelLanguages = NoLocalNovelLanguages,
    )
}
