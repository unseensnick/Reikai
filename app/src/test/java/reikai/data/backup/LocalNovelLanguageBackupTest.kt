package reikai.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
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
import reikai.data.novel.local.LocalNovelLanguageRepositoryImpl
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.model.Novel
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import reikai.novel.local.LocalNovelSource
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * A local novel's language is read from its files only when they are read again, which a restored novel
 * with chapters waits on, so a backup carries it, as a local manga's rides in its backed-up memo.
 */
class LocalNovelLanguageBackupTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var languages: LocalNovelLanguageRepositoryImpl

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        languages = LocalNovelLanguageRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a backup carries a local novel's language`() = runTest {
        languages.set("Book", "ja")

        backupOf(Novel.create().copy(id = 1L, source = LocalNovelSource.ID, url = "Book")).language shouldBe "ja"
    }

    @Test
    fun `a backup carries no language for an online novel`() = runTest {
        languages.set("Book", "ja")

        backupOf(Novel.create().copy(id = 1L, source = "plugin", url = "Book")).language shouldBe null
    }

    @Test
    fun `a restore puts back a local novel's language`() = runTest {
        restorer().restore(BackupNovel(source = LocalNovelSource.ID, url = "Book", language = "ja"), emptyList())

        languages.get("Book") shouldBe "ja"
    }

    @Test
    fun `a backup without a language keeps the one already read`() = runTest {
        languages.set("Book", "ja")

        restorer().restore(BackupNovel(source = LocalNovelSource.ID, url = "Book"), emptyList())

        languages.get("Book") shouldBe "ja"
    }

    private suspend fun backupOf(novel: Novel) = NovelBackupCreator(
        manualOrder = FakeLibraryManualOrderRepository(),
        novelRepository = mockk(),
        novelChapterRepository = mockk(),
        categoryRepository = mockk(),
        novelTrackRepository = mockk(),
        mergeGroupRepository = mockk(),
        customNovelInfoRepository = mockk(),
        novelHistoryRepository = mockk(),
        novelSourceManager = mockk(),
        chapterNumberOverrides = NoChapterNumberOverrides,
        copyMarks = InMemoryChapterCopyMarks(),
        readingLog = NoReadingLog,
        readingPace = NoReadingPace,
        localNovelLanguages = languages,
    ).base(listOf(novel)).single()

    private fun restorer() = NovelRestorer(
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
        readingLog = NoReadingLog,
        readingPace = NoReadingPace,
        localNovelLanguages = languages,
    )
}
