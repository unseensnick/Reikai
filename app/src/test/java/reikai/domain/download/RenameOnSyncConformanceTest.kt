package reikai.domain.download

import android.app.NotificationManager
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadProvider
import reikai.novel.host.ChapterItem
import reikai.util.realTimeSourceContacts
import reikai.util.stubUniFileStatics
import reikai.util.testAppScope
import reikai.util.unstubUniFileStatics
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * A downloaded chapter the source re-titles has its download renamed in the same sync, over each type's
 * real sync and database, and one that is not downloaded has nothing renamed. Manga's downloader is faked
 * at its rename; the novel one is real down to the file on disk, since its downloaded check is the file.
 */
class RenameOnSyncConformanceTest {

    @TempDir
    lateinit var root: File

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        stubUniFileStatics()
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
        unstubUniFileStatics()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a re-titled downloaded chapter has its download renamed`(type: Type) = runTest {
        type.seed(database, driver)

        type.renamesOnSync(database, root, "Chapter 5 Retitled", downloaded = true) shouldBe
            listOf("Chapter 5" to "Chapter 5 Retitled")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter whose title stays has nothing renamed`(type: Type) = runTest {
        type.seed(database, driver)

        type.renamesOnSync(database, root, "Chapter 5", downloaded = true) shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a re-titled chapter that is not downloaded has nothing renamed`(type: Type) = runTest {
        type.seed(database, driver)

        type.renamesOnSync(database, root, "Chapter 5 Retitled", downloaded = false) shouldBe emptyList()
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

            override suspend fun renamesOnSync(
                database: Database,
                root: File,
                newName: String,
                downloaded: Boolean,
            ): List<Pair<String, String>> {
                val renames = mutableListOf<Pair<String, String>>()
                val downloads = mockk<DownloadManager>(relaxed = true) {
                    every { isChapterDownloaded(any(), any(), any(), any(), any()) } returns downloaded
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
                    chapterCopyMarks = InMemoryChapterCopyMarks(),
                    localTombstones = NoChapterTombstones,
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

            override suspend fun renamesOnSync(
                database: Database,
                root: File,
                newName: String,
                downloaded: Boolean,
            ): List<Pair<String, String>> {
                val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
                val provider = NovelDownloadProvider(
                    scope = testAppScope(),
                    context = mockk(),
                    storageManager = mockk<StorageManager> {
                        every { getNovelDownloadsDirectory() } returns UniFile.fromFile(root)
                        every { changes } returns MutableSharedFlow()
                    },
                    downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
                    libraryPreferences = libraryPreferences,
                    novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
                )
                val folder = File(root, "src/T").apply { mkdirs() }
                val oldFile = File(folder, provider.chapterFileName("Chapter 5", URL))
                val newFile = File(folder, provider.chapterFileName(newName, URL))
                if (downloaded) oldFile.writeText("<p>Chapter 5</p>")
                sync(database, newName, manager(provider))
                val moved = oldFile != newFile && newFile.exists() && !oldFile.exists()
                return if (moved) listOf("Chapter 5" to newName) else emptyList()
            }

            private fun manager(provider: NovelDownloadProvider) = NovelDownloadManager(
                scope = testAppScope(),
                context = mockk(relaxed = true) {
                    every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
                    every { getSystemService(NotificationManager::class.java) } returns mockk(relaxed = true)
                },
                provider = provider,
                cache = mockk(relaxed = true),
                chapterRepo = mockk(),
                novelRepo = mockk(),
                sourceManager = mockk(),
                installer = mockk(),
                downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
                sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
                novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
                saver = mockk(),
                securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
                adultChecker = mockk(),
                sourceTitles = mockk(),
                getEntryCustomInfo = mockk(),
                sourceContacts = realTimeSourceContacts(),
            )

            private suspend fun sync(database: Database, name: String, downloads: NovelDownloadManager?) {
                syncChaptersWithNovelSource(
                    listOf(ChapterItem(name = name, path = URL, chapterNumber = 5.0)),
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    LibraryPreferences(InMemoryPreferenceStore()),
                    ChapterNumberOverrideRepositoryImpl(database),
                    InMemoryChapterCopyMarks(),
                    NoChapterTombstones,
                    novelDownloadManager = downloads,
                )
            }
        },
        ;

        abstract suspend fun seed(database: Database, driver: JdbcSqliteDriver)

        /**
         * Syncs the stored chapter under [newName], with its download in [root] or not by [downloaded]; each
         * download renamed, old name to new.
         */
        abstract suspend fun renamesOnSync(
            database: Database,
            root: File,
            newName: String,
            downloaded: Boolean,
        ): List<Pair<String, String>>
    }

    private companion object {
        const val OWNER = 1L
        const val URL = "/c5"
    }
}
