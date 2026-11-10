package reikai.domain.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import reikai.novel.host.ChapterItem
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/**
 * A source that gives no upload date or group means it does not know one, on both types: a stored
 * chapter keeps the value it had, and a new chapter is dated by the sync rather than stored undated.
 * Each type's real sync over a real database.
 */
class UploadDateSyncConformanceTest {

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
    fun `a source that stops dating a stored chapter leaves its date alone`(type: Type) = runTest {
        type.seed(driver)
        type.sync(database, name = "Chapter 1", dated = true)

        type.sync(database, name = "Chapter 1 renamed", dated = false)

        type.storedDate(database) shouldBe DATED
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a source that stops naming a stored chapter's group leaves its group alone`(type: Type) = runTest {
        type.seed(driver)
        type.sync(database, name = "Chapter 1", dated = true, scanlator = "Group")

        type.sync(database, name = "Chapter 1 renamed", dated = true, scanlator = null)

        type.storedScanlator(database) shouldBe "Group"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a new chapter the source gives no date is dated by the sync`(type: Type) = runTest {
        type.seed(driver)

        type.sync(database, name = "Chapter 1", dated = false)

        type.storedDate(database).shouldNotBeNull()
    }

    enum class Type {
        MANGA {
            override suspend fun seed(driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                        "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                        "($OWNER, 1, 'u', 'T', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                    0,
                ).await()
            }

            override suspend fun sync(database: Database, name: String, dated: Boolean, scanlator: String?) {
                val preferences = LibraryPreferences(InMemoryPreferenceStore())
                SyncChaptersWithSource(
                    downloadManager = mockk(relaxed = true),
                    downloadProvider = DownloadProvider(mockk(), mockk(), preferences),
                    chapterRepository = ChapterRepositoryImpl(database),
                    shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                    updateManga = mockk(relaxed = true),
                    getExcludedScanlators = mockk(relaxed = true),
                    libraryPreferences = preferences,
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                    chapterCopyMarks = InMemoryChapterCopyMarks(),
                ).await(
                    listOf(
                        SChapter.create().apply {
                            url = URL
                            this.name = name
                            chapter_number = 1f
                            // The source API writes 0 for a date it does not know
                            date_upload = if (dated) DATED.toEpochMilliseconds() else 0L
                            this.scanlator = scanlator
                        },
                    ),
                    Manga.create().copy(id = OWNER, title = "T", source = 1),
                    mockk<Source> { every { id } returns 1L },
                )
            }

            override suspend fun storedDate(database: Database) =
                ChapterRepositoryImpl(
                    database,
                ).getChapterByMangaId(OWNER, applyScanlatorFilter = false).single().dateUpload

            override suspend fun storedScanlator(database: Database) =
                ChapterRepositoryImpl(
                    database,
                ).getChapterByMangaId(OWNER, applyScanlatorFilter = false).single().scanlator
        },
        NOVEL {
            override suspend fun seed(driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($OWNER, 'src', 'u', 'T', 0, 0, 0, 0)",
                    0,
                ).await()
            }

            override suspend fun sync(database: Database, name: String, dated: Boolean, scanlator: String?) {
                syncChaptersWithNovelSource(
                    listOf(
                        ChapterItem(
                            name = name,
                            path = URL,
                            chapterNumber = 1.0,
                            releaseTime = DATED.toString().takeIf { dated },
                            scanlator = scanlator,
                        ),
                    ),
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    LibraryPreferences(InMemoryPreferenceStore()),
                    ChapterNumberOverrideRepositoryImpl(database),
                    InMemoryChapterCopyMarks(),
                )
            }

            override suspend fun storedDate(database: Database) =
                NovelChapterRepositoryImpl(database).getByNovelId(OWNER).single().dateUpload

            override suspend fun storedScanlator(database: Database) =
                NovelChapterRepositoryImpl(database).getByNovelId(OWNER).single().scanlator
        },
        ;

        abstract suspend fun seed(driver: JdbcSqliteDriver)

        /** Syncs a source listing one chapter at [URL] named [name], dated [DATED] or undated, by [scanlator]. */
        abstract suspend fun sync(database: Database, name: String, dated: Boolean, scanlator: String? = null)

        abstract suspend fun storedDate(database: Database): Instant?

        abstract suspend fun storedScanlator(database: Database): String?
    }

    private companion object {
        const val OWNER = 1L
        const val URL = "/c1"
        val DATED = Instant.parse("2024-01-02T00:00:00Z")
    }
}
