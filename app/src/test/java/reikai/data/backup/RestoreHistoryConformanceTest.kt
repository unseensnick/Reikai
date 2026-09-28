package reikai.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.mangaRestorer
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * A backup can carry one history entry per copy of a duplicated chapter (mihon 553762fae). Restore counts
 * them as one entry, the latest read and the total time, and never lowers what the device already has.
 * Runs each type's real restorer over real SQL.
 */
class RestoreHistoryConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
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

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `two copies of one chapter restore as one entry with the later read and the times added`(type: Type) =
        runTest {
            type.seed(driver, device = Stored(readAt = 50L, duration = 2L))

            type.restore(
                database,
                copies = listOf(Copy(readAt = 200L, duration = 4L), Copy(readAt = 100L, duration = 3L)),
            )

            type.history(database) shouldBe Stored(readAt = 200L, duration = 7L)
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a device that has read longer keeps its time and its later read`(type: Type) = runTest {
        type.seed(driver, device = Stored(readAt = 500L, duration = 10L))

        type.restore(database, copies = listOf(Copy(readAt = 100L, duration = 3L), Copy(readAt = 200L, duration = 4L)))

        type.history(database) shouldBe Stored(readAt = 500L, duration = 10L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a removed entry restored over a removed entry stays read at 0`(type: Type) = runTest {
        type.seed(driver, device = Stored(readAt = 0L, duration = 1L))

        type.restore(database, copies = listOf(Copy(readAt = 0L, duration = 5L)))

        type.history(database) shouldBe Stored(readAt = 0L, duration = 5L)
    }

    data class Copy(val readAt: Long, val duration: Long)

    data class Stored(val readAt: Long?, val duration: Long)

    /** One series with one chapter at url "c", optionally already read on the device. */
    enum class Type {
        MANGA {
            override fun statements(device: Stored?) = listOfNotNull(
                "INSERT INTO mangas(_id, source, url, title, status, initialized, viewer, chapter_flags, " +
                    "cover_last_modified, favorite_at) VALUES (1, 1, 'u', 'T', 0, 0, 0, 0, 0, 0)",
                "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(1, 1, 'c', 'C', NULL, 1, 0, 0, 1.0, 0, 0, 0)",
                device?.let {
                    "INSERT INTO history(chapter_id, last_read, time_read) VALUES (1, ${it.readAt}, ${it.duration})"
                },
            )

            override suspend fun restore(database: Database, copies: List<Copy>) {
                mangaRestorer(database).restore(
                    listOf(
                        BackupManga(
                            source = 1L,
                            url = "u",
                            title = "T",
                            chapters = listOf(BackupChapter(url = "c", name = "C", read = true)),
                            history = copies.map {
                                BackupHistory(url = "c", lastRead = it.readAt, readDuration = it.duration)
                            },
                        ),
                    ),
                    emptyList(),
                )
            }

            override suspend fun history(database: Database) = database.historyQueries
                .getHistoryByChapterUrlAndMangaId("c", 1L)
                .awaitAsOneOrNull()
                ?.let { Stored(it.last_read?.time, it.time_read) }
        },
        NOVEL {
            override fun statements(device: Stored?) = listOfNotNull(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES (1, 'src', 'u', 'T', 0, 0, 0, 0)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES (1, 1, 'c', 'C', 1, 0, 0, 1.0, 0, 0, 0)",
                device?.let {
                    "INSERT INTO novel_history(chapter_id, last_read, time_read) VALUES (1, ${it.readAt}, ${it.duration})"
                },
            )

            override suspend fun restore(database: Database, copies: List<Copy>) {
                NovelRestorer(
                    novelRepository = NovelRepositoryImpl(database),
                    novelChapterRepository = NovelChapterRepositoryImpl(database),
                    categoryRepository = mockk(relaxed = true),
                    novelTrackRepository = mockk(relaxed = true),
                    restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
                    setCustomNovelInfo = mockk(relaxed = true),
                    novelHistoryRepository = NovelHistoryRepositoryImpl(database),
                ).restore(
                    BackupNovel(
                        source = "src",
                        url = "u",
                        title = "T",
                        chapters = listOf(BackupNovelChapter(url = "c", name = "C", read = true, chapterNumber = 1.0)),
                        history = copies.map {
                            BackupNovelHistory(url = "c", lastRead = it.readAt, readDuration = it.duration)
                        },
                    ),
                    emptyList(),
                )
            }

            override suspend fun history(database: Database) =
                NovelHistoryRepositoryImpl(database).getHistoryByNovelId(1L).singleOrNull()
                    ?.let { Stored(it.readAt, it.readDuration) }
        },
        ;

        abstract fun statements(device: Stored?): List<String>

        abstract suspend fun restore(database: Database, copies: List<Copy>)

        abstract suspend fun history(database: Database): Stored?

        suspend fun seed(driver: JdbcSqliteDriver, device: Stored?) =
            statements(device).forEach { driver.execute(null, it, 0).await() }
    }
}
