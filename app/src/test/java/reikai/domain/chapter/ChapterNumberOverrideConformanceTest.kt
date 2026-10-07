package reikai.domain.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.merge.MergedChapterUnitRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.domain.library.ContentType
import reikai.domain.merge.ReconcileMergedChapters
import reikai.novel.host.ChapterItem
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * A chapter number the user corrected, over each type's real sync and database: a refresh keeps it
 * whatever the source says, and putting the source's number back leaves nothing behind.
 */
class ChapterNumberOverrideConformanceTest {

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

    private val overrides get() = ChapterNumberOverrideRepositoryImpl(database)

    private val editor get() = EditChapterNumber(
        overrides,
        ReconcileMergedChapters(MergedChapterUnitRepositoryImpl(database), emptySet()),
    )

    private suspend fun corrected(type: Type, number: Double) {
        type.seed(database, driver)
        editor.save(editor.edit(type.contentType, OWNER, URL, "Chapter 5", type.number(database, URL)), number)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a sync whose source still says 5 keeps the corrected 6`(type: Type) = runTest {
        corrected(type, 6.0)

        type.sync(database, URL to 5.0)

        type.number(database, URL) shouldBe 6.0
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a sync whose source still says 5 writes nothing`(type: Type) = runTest {
        corrected(type, 6.0)

        type.sync(database, URL to 5.0) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a source that renumbers keeps the correction and putting it back restores the new number`(type: Type) =
        runTest {
            corrected(type, 6.0)

            type.sync(database, URL to 5.5)
            val kept = type.number(database, URL)
            editor.save(editor.edit(type.contentType, OWNER, URL, "Chapter 5", kept), null)

            listOf(kept, type.number(database, URL)) shouldBe listOf(6.0, 5.5)
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter the source drops and lists again keeps its correction`(type: Type) = runTest {
        corrected(type, 6.0)

        type.sync(database, OTHER to 7.0)
        type.sync(database, URL to 5.0, OTHER to 7.0)

        type.number(database, URL) shouldBe 6.0
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `correcting a chapter back to the source's number leaves no correction`(type: Type) = runTest {
        corrected(type, 6.0)

        editor.save(editor.edit(type.contentType, OWNER, URL, "Chapter 5", 6.0), 5.0)

        overrides.getByOwner(type.contentType, OWNER) shouldBe emptyMap()
    }

    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA) {
            override suspend fun seed(database: Database, driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                        "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                        "($OWNER, 1, 'u', 'T', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                    0,
                ).await()
                sync(database, URL to 5.0)
            }

            override suspend fun sync(database: Database, vararg chapters: Pair<String, Double>): Boolean {
                val updateManga = mockk<UpdateManga>(relaxed = true)
                SyncChaptersWithSource(
                    downloadManager = mockk(relaxed = true),
                    downloadProvider = mockk(relaxed = true),
                    chapterRepository = ChapterRepositoryImpl(database),
                    shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                    updateManga = updateManga,
                    getExcludedScanlators = mockk(relaxed = true),
                    libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                ).await(
                    chapters.map { (url, number) ->
                        SChapter.create().apply {
                            this.url = url
                            name = "Chapter $number"
                            chapter_number = number.toFloat()
                            date_upload = 1000L
                        }
                    },
                    Manga.create().copy(id = OWNER, title = "T", source = 1),
                    mockk<Source> { every { id } returns 1L },
                )
                return runCatching { coVerify(exactly = 1) { updateManga.awaitUpdateLastUpdate(OWNER) } }.isSuccess
            }

            override suspend fun number(database: Database, url: String) =
                ChapterRepositoryImpl(database).getChapterByMangaId(OWNER).single { it.url == url }.chapterNumber
        },
        NOVEL(ContentType.NOVELS) {
            override suspend fun seed(database: Database, driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($OWNER, 'src', 'u', 'T', 0, 0, 0, 0)",
                    0,
                ).await()
                sync(database, URL to 5.0)
            }

            override suspend fun sync(database: Database, vararg chapters: Pair<String, Double>): Boolean =
                syncChaptersWithNovelSource(
                    chapters.map { (url, number) ->
                        ChapterItem(name = "Chapter $number", path = url, chapterNumber = number)
                    },
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    LibraryPreferences(InMemoryPreferenceStore()),
                    ChapterNumberOverrideRepositoryImpl(database),
                ).changed

            override suspend fun number(database: Database, url: String) =
                NovelChapterRepositoryImpl(database).getByNovelId(OWNER).single { it.url == url }.chapterNumber
        },
        ;

        abstract suspend fun seed(database: Database, driver: JdbcSqliteDriver)

        /** Runs the type's real sync over [chapters], url to the number the source gives; true when it wrote. */
        abstract suspend fun sync(database: Database, vararg chapters: Pair<String, Double>): Boolean

        abstract suspend fun number(database: Database, url: String): Double
    }

    private companion object {
        const val OWNER = 1L
        const val URL = "/c5"
        const val OTHER = "/c7"
    }
}
