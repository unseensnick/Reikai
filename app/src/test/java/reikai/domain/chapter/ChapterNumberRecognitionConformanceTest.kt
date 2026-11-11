package reikai.domain.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
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
import reikai.data.novel.NovelChapterSyncRules
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
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * Chapter numbers read from the whole list, over each type's real sync and database: a chapter without a
 * number takes one from its neighbours, and a refresh that renumbers a stored chapter keeps what the user
 * set on it. Lists are given newest first; the novel sync gets them oldest first, as plugins list them.
 */
class ChapterNumberRecognitionConformanceTest {

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

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter without a number between two numbered ones is numbered from them`(type: Type) = runTest {
        type.seed(driver)

        type.sync(database, FOUR, NIGHT, TWO)

        type.state(database, NIGHT.first).number shouldBe 3.0
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a refresh that renumbers a corrected chapter keeps the correction and records the new number`(
        type: Type,
    ) = runTest {
        type.seed(driver)
        type.sync(database, FOUR, NIGHT)
        val stored = type.state(database, NIGHT.first).number
        editor.save(editor.edit(type.contentType, OWNER, NIGHT.first, NIGHT.second, stored, null), 7.0)

        type.sync(database, FOUR, NIGHT, TWO)
        val kept = type.state(database, NIGHT.first).number
        editor.save(editor.edit(type.contentType, OWNER, NIGHT.first, NIGHT.second, kept, null), null)

        listOf(kept, type.state(database, NIGHT.first).number) shouldBe listOf(7.0, 3.0)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a refresh that renumbers a read chapter keeps it read`(type: Type) = runTest {
        type.seed(driver)
        type.sync(database, FOUR, NIGHT)
        type.markRead(database, NIGHT.first)

        type.sync(database, FOUR, NIGHT, TWO)

        type.state(database, NIGHT.first) shouldBe State(3.0, read = true)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a new chapter numbered like a read chapter the same refresh renumbers arrives unread`(type: Type) =
        runTest {
            type.seed(driver)
            type.sync(database, FOUR, NIGHT, TWO)
            type.markRead(database, NIGHT.first)

            type.sync(database, FOUR, "/3" to "Chapter 3", NIGHT, TWO)

            type.state(database, "/3") shouldBe State(3.0, read = false)
        }

    data class State(val number: Double, val read: Boolean)

    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA) {
            override suspend fun seed(driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                        "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                        "($OWNER, 1, 'u', 'T', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                    0,
                ).await()
            }

            override suspend fun sync(database: Database, vararg chapters: Pair<String, String>) {
                SyncChaptersWithSource(
                    downloadManager = mockk(relaxed = true),
                    downloadProvider = mockk(relaxed = true),
                    chapterRepository = ChapterRepositoryImpl(database),
                    shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                    updateManga = mockk(relaxed = true),
                    getExcludedScanlators = mockk(relaxed = true),
                    libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                    chapterCopyMarks = InMemoryChapterCopyMarks(),
                    localTombstones = NoChapterTombstones,
                ).await(
                    chapters.map { (url, name) ->
                        SChapter.create().apply {
                            this.url = url
                            this.name = name
                            date_upload = 1000L
                        }
                    },
                    Manga.create().copy(id = OWNER, title = "T", source = 1),
                    mockk<Source> { every { id } returns 1L },
                )
            }

            override suspend fun markRead(database: Database, url: String) {
                val repository = ChapterRepositoryImpl(database)
                val chapter = repository.getChapterByMangaId(OWNER).single { it.url == url }
                repository.update(ChapterUpdate(chapter.id) { read = true })
            }

            override suspend fun state(database: Database, url: String) =
                ChapterRepositoryImpl(database).getChapterByMangaId(OWNER).single { it.url == url }
                    .let { State(it.chapterNumber, it.read) }
        },
        NOVEL(ContentType.NOVELS) {
            override suspend fun seed(driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($OWNER, 'src', 'u', 'T', 0, 0, 0, 0)",
                    0,
                ).await()
            }

            override suspend fun sync(database: Database, vararg chapters: Pair<String, String>) {
                syncChaptersWithNovelSource(
                    chapters.reversed().map { (url, name) -> ChapterItem(name = name, path = url) },
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    LibraryPreferences(InMemoryPreferenceStore()),
                    NovelChapterSyncRules(
                        ChapterNumberOverrideRepositoryImpl(database),
                        InMemoryChapterCopyMarks(),
                        NoChapterTombstones,
                    ),
                )
            }

            override suspend fun markRead(database: Database, url: String) {
                val repository = NovelChapterRepositoryImpl(database)
                repository.setReadBulk(listOf(repository.getByNovelId(OWNER).single { it.url == url }.id), true)
            }

            override suspend fun state(database: Database, url: String) =
                NovelChapterRepositoryImpl(database).getByNovelId(OWNER).single { it.url == url }
                    .let { State(it.chapterNumber, it.read) }
        },
        ;

        abstract suspend fun seed(driver: JdbcSqliteDriver)

        /** Runs the type's real sync over [chapters], url to name, newest first. */
        abstract suspend fun sync(database: Database, vararg chapters: Pair<String, String>)

        abstract suspend fun markRead(database: Database, url: String)

        abstract suspend fun state(database: Database, url: String): State
    }

    private companion object {
        const val OWNER = 1L
        val FOUR = "/4" to "Chapter 4"
        val NIGHT = "/night" to "The Long Night"
        val TWO = "/2" to "Chapter 2"
    }
}
