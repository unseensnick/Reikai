package reikai.domain.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.ChapterItem
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * A queued download of a chapter the source no longer lists is cancelled by the sync that drops the
 * chapter, since it could only fail (mihonapp/mihon#3845). Each type's real sync and database, with
 * only the downloader faked as having every chapter queued.
 */
class RemovedChapterQueueConformanceTest {

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
    fun `a chapter the source dropped has its queued download cancelled`(type: Type) = runTest {
        val dropped = type.seed(database, driver)

        type.cancelledOnSync(database, listOf(KEPT)) shouldBe listOf(dropped)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a sync that drops nothing cancels nothing`(type: Type) = runTest {
        type.seed(database, driver)

        type.cancelledOnSync(database, listOf(KEPT, DROPPED)) shouldBe emptyList()
    }

    enum class Type {
        MANGA {
            override suspend fun seed(database: Database, driver: JdbcSqliteDriver): Long {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                        "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                        "($OWNER, 1, 'u', 'T', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                    0,
                ).await()
                sync(database, listOf(KEPT, DROPPED), mockk(relaxed = true))
                val stored = ChapterRepositoryImpl(database).getChapterByMangaId(OWNER, applyScanlatorFilter = false)
                return stored.single { it.url == DROPPED }.id
            }

            override suspend fun cancelledOnSync(database: Database, urls: List<String>): List<Long> {
                val cancelled = mutableListOf<Long>()
                val downloads = mockk<DownloadManager>(relaxed = true) {
                    every { getQueuedDownloadOrNull(any()) } answers
                        { Download(mockk(), Manga.create(), Chapter.create().copy(id = firstArg())) }
                    coEvery { cancelQueuedDownloads(any()) } answers
                        { cancelled += firstArg<List<Download>>().map { it.chapter.id } }
                }
                sync(database, urls, downloads)
                return cancelled
            }

            private suspend fun sync(database: Database, urls: List<String>, downloads: DownloadManager) {
                val preferences = LibraryPreferences(InMemoryPreferenceStore())
                SyncChaptersWithSource(
                    downloadManager = downloads,
                    downloadProvider = DownloadProvider(mockk(), mockk(), preferences),
                    chapterRepository = ChapterRepositoryImpl(database),
                    shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                    updateManga = mockk(relaxed = true),
                    getExcludedScanlators = mockk(relaxed = true),
                    libraryPreferences = preferences,
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                    chapterCopyMarks = InMemoryChapterCopyMarks(),
                ).await(
                    urls.mapIndexed { i, url ->
                        SChapter.create().apply {
                            this.url = url
                            name = "Chapter $url"
                            chapter_number = i + 1f
                            date_upload = 1000L
                        }
                    },
                    Manga.create().copy(id = OWNER, title = "T", source = 1),
                    mockk<Source> { every { id } returns 1L },
                )
            }
        },
        NOVEL {
            override suspend fun seed(database: Database, driver: JdbcSqliteDriver): Long {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($OWNER, 'src', 'u', 'T', 0, 0, 0, 0)",
                    0,
                ).await()
                sync(database, listOf(KEPT, DROPPED), null)
                return NovelChapterRepositoryImpl(database).getByNovelId(OWNER).single { it.url == DROPPED }.id
            }

            override suspend fun cancelledOnSync(database: Database, urls: List<String>): List<Long> {
                val cancelled = mutableListOf<Long>()
                val downloads = mockk<NovelDownloadManager>(relaxed = true) {
                    every { cancelDownloads(any()) } answers { cancelled += firstArg<List<Long>>() }
                }
                sync(database, urls, downloads)
                return cancelled
            }

            private suspend fun sync(database: Database, urls: List<String>, downloads: NovelDownloadManager?) {
                syncChaptersWithNovelSource(
                    urls.mapIndexed { i, url ->
                        ChapterItem(name = "Chapter $url", path = url, chapterNumber = i + 1.0)
                    },
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    LibraryPreferences(InMemoryPreferenceStore()),
                    ChapterNumberOverrideRepositoryImpl(database),
                    InMemoryChapterCopyMarks(),
                    novelDownloadManager = downloads,
                )
            }
        },
        ;

        /** Stores [KEPT] and [DROPPED]; the id the dropped chapter was stored under. */
        abstract suspend fun seed(database: Database, driver: JdbcSqliteDriver): Long

        /** Syncs a source listing [urls]; each chapter id whose queued download was cancelled. */
        abstract suspend fun cancelledOnSync(database: Database, urls: List<String>): List<Long>
    }

    private companion object {
        const val OWNER = 1L
        const val KEPT = "/c1"
        const val DROPPED = "/c2"
    }
}
