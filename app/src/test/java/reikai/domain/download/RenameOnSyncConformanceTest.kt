package reikai.domain.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
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
import reikai.domain.novel.model.NovelChapter
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
 * A downloaded chapter the source re-titles has its download renamed in the same sync, over each type's
 * real sync and database with only the downloader faked, so the file still answers to its chapter.
 */
class RenameOnSyncConformanceTest {

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
    fun `a re-titled downloaded chapter has its download renamed`(type: Type) = runTest {
        type.seed(database, driver)

        type.renamesOnSync(database, "Chapter 5 Retitled") shouldBe listOf("Chapter 5" to "Chapter 5 Retitled")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter whose title stays has nothing renamed`(type: Type) = runTest {
        type.seed(database, driver)

        type.renamesOnSync(database, "Chapter 5") shouldBe emptyList()
    }

    enum class Type {
        MANGA {
            override suspend fun seed(database: Database, driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                        "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                        "($OWNER, 1, 'u', 'T', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                    0,
                ).await()
                sync(database, "Chapter 5", mockk(relaxed = true))
            }

            override suspend fun renamesOnSync(database: Database, newName: String): List<Pair<String, String>> {
                val renames = mutableListOf<Pair<String, String>>()
                val downloads = mockk<DownloadManager>(relaxed = true) {
                    every { isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
                    coEvery { renameChapter(any(), any(), any(), any()) } answers
                        { renames += thirdArg<Chapter>().name to arg<Chapter>(3).name }
                }
                sync(database, newName, downloads)
                return renames
            }

            private suspend fun sync(database: Database, name: String, downloads: DownloadManager) {
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
                ).await(
                    listOf(
                        SChapter.create().apply {
                            url = URL
                            this.name = name
                            chapter_number = 5f
                            date_upload = 1000L
                        },
                    ),
                    Manga.create().copy(id = OWNER, title = "T", source = 1),
                    mockk<Source> { every { id } returns 1L },
                )
            }
        },
        NOVEL {
            override suspend fun seed(database: Database, driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($OWNER, 'src', 'u', 'T', 0, 0, 0, 0)",
                    0,
                ).await()
                sync(database, "Chapter 5", null)
            }

            override suspend fun renamesOnSync(database: Database, newName: String): List<Pair<String, String>> {
                val renames = mutableListOf<Pair<String, String>>()
                val downloads = mockk<NovelDownloadManager>(relaxed = true) {
                    coEvery { renameChapter(any(), any(), any()) } answers
                        { renames += secondArg<NovelChapter>().name to thirdArg<NovelChapter>().name }
                }
                sync(database, newName, downloads)
                return renames
            }

            private suspend fun sync(database: Database, name: String, downloads: NovelDownloadManager?) {
                syncChaptersWithNovelSource(
                    listOf(ChapterItem(name = name, path = URL, chapterNumber = 5.0)),
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    LibraryPreferences(InMemoryPreferenceStore()),
                    ChapterNumberOverrideRepositoryImpl(database),
                    novelDownloadManager = downloads,
                )
            }
        },
        ;

        abstract suspend fun seed(database: Database, driver: JdbcSqliteDriver)

        /** Syncs the stored chapter under [newName]; each download rename asked for, old name to new. */
        abstract suspend fun renamesOnSync(database: Database, newName: String): List<Pair<String, String>>
    }

    private companion object {
        const val OWNER = 1L
        const val URL = "/c5"
    }
}
