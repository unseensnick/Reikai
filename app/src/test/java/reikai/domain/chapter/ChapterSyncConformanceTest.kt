package reikai.domain.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.novel.host.ChapterItem
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * What each type's real sync over a real database reports back: whether it found a release, which the
 * next update is counted from, and which chapters it calls new. Both reach the rules through
 * [chapterArrivals] and [foundRelease]; the glue around them (the duplicate setting, and leaving a
 * changed or duplicate arrival out of the new chapters) is written in each sync, so it is pinned here.
 */
class ChapterSyncConformanceTest {

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
    fun `a first fetch is the back catalogue, not a release`(type: Type) = runTest {
        type.seed(driver)

        type.sync(database, Listed("/c1", 1.0), Listed("/c2", 2.0)).foundRelease shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a new chapter on a stored entry is a release`(type: Type) = runTest {
        type.seed(driver)
        type.sync(database, Listed("/c1", 1.0))

        type.sync(database, Listed("/c2", 2.0), Listed("/c1", 1.0)).foundRelease shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter re-listed under a removed number is not a release`(type: Type) = runTest {
        type.seed(driver)
        type.sync(database, Listed("/c2", 2.0), Listed("/c1", 1.0))

        type.sync(database, Listed("/c2-new", 2.0), Listed("/c1", 1.0)).foundRelease shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a new chapter only from an excluded group is not a release`(type: Type) = runTest {
        assumeTrue(type.excludesGroups, "novels have no excluded groups")
        type.seed(driver)
        type.sync(database, Listed("/c1", 1.0))

        type.sync(database, Listed("/c2", 2.0, EXCLUDED), Listed("/c1", 1.0)).foundRelease shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a new chapter numbered like a read one arrives read and is not reported new`(type: Type) = runTest {
        type.seed(driver)
        type.sync(database, Listed("/c5-a", 5.0))
        type.markRead(driver, "/c5-a")

        val synced = type.sync(database, Listed("/c5-b", 5.0), Listed("/c5-a", 5.0))

        (type.isRead(database, "/c5-b") to synced.newUrls) shouldBe (true to emptyList())
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `with duplicate marking off, a new chapter numbered like a read one is unread and new`(type: Type) =
        runTest {
            type.seed(driver)
            type.sync(database, Listed("/c5-a", 5.0))
            type.markRead(driver, "/c5-a")

            val synced = type.sync(database, Listed("/c5-b", 5.0), Listed("/c5-a", 5.0), markDuplicates = false)

            (type.isRead(database, "/c5-b") to synced.newUrls) shouldBe (false to listOf("/c5-b"))
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter re-listed under a removed number keeps its read state and is not reported new`(type: Type) =
        runTest {
            type.seed(driver)
            type.sync(database, Listed("/c2", 2.0), Listed("/c1", 1.0))
            type.markRead(driver, "/c2")

            val synced = type.sync(database, Listed("/c2-new", 2.0), Listed("/c1", 1.0))

            (type.isRead(database, "/c2-new") to synced.newUrls) shouldBe (true to emptyList())
        }

    data class Listed(val url: String, val number: Double, val scanlator: String? = null)

    /** What a sync reports: whether it found a release, and the urls of the chapters it calls new. */
    data class Synced(val foundRelease: Boolean, val newUrls: List<String>)

    enum class Type(val excludesGroups: Boolean) {
        MANGA(excludesGroups = true) {
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

            override suspend fun sync(database: Database, vararg chapters: Listed, markDuplicates: Boolean): Synced {
                val found = slot<Boolean>()
                val updateManga = mockk<UpdateManga> {
                    coEvery {
                        awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any(), any(), capture(found))
                    } returns
                        true
                }
                val excluded = mockk<GetExcludedScanlators> { coEvery { await(OWNER) } returns setOf(EXCLUDED) }
                val new = SyncChaptersWithSource(
                    downloadManager = mockk(relaxed = true),
                    downloadProvider = mockk(relaxed = true),
                    chapterRepository = ChapterRepositoryImpl(database),
                    shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                    updateManga = updateManga,
                    getExcludedScanlators = excluded,
                    libraryPreferences = libraryPreferences(markDuplicates),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                ).await(
                    chapters.map { listed ->
                        SChapter.create().apply {
                            url = listed.url
                            name = "Chapter ${listed.number}"
                            chapter_number = listed.number.toFloat()
                            scanlator = listed.scanlator
                        }
                    },
                    Manga.create().copy(id = OWNER, title = "T", source = 1),
                    mockk<Source> { every { id } returns 1L },
                )
                return Synced(found.captured, new.map { it.url })
            }

            override suspend fun markRead(driver: JdbcSqliteDriver, url: String) {
                driver.execute(null, "UPDATE chapter SET user_read = 1 WHERE remote_url = '$url'", 0).await()
            }

            override suspend fun isRead(database: Database, url: String) =
                ChapterRepositoryImpl(database).getChapterByMangaId(OWNER).single { it.url == url }.read
        },
        NOVEL(excludesGroups = false) {
            override suspend fun seed(driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($OWNER, 'src', 'u', 'T', 0, 0, 0, 0)",
                    0,
                ).await()
            }

            override suspend fun sync(database: Database, vararg chapters: Listed, markDuplicates: Boolean): Synced {
                val result = syncChaptersWithNovelSource(
                    chapters.map {
                        ChapterItem(name = "Chapter ${it.number}", path = it.url, chapterNumber = it.number)
                    },
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    libraryPreferences(markDuplicates),
                    ChapterNumberOverrideRepositoryImpl(database),
                )
                return Synced(result.foundRelease, result.newChapters.map { it.url })
            }

            override suspend fun markRead(driver: JdbcSqliteDriver, url: String) {
                driver.execute(null, "UPDATE novel_chapters SET read = 1 WHERE url = '$url'", 0).await()
            }

            override suspend fun isRead(database: Database, url: String) =
                NovelChapterRepositoryImpl(database).getByNovelId(OWNER).single { it.url == url }.read
        },
        ;

        abstract suspend fun seed(driver: JdbcSqliteDriver)

        /** Syncs a source listing [chapters], newest first; [markDuplicates] is the "mark duplicate read" setting. */
        abstract suspend fun sync(database: Database, vararg chapters: Listed, markDuplicates: Boolean = true): Synced

        abstract suspend fun markRead(driver: JdbcSqliteDriver, url: String)

        abstract suspend fun isRead(database: Database, url: String): Boolean
    }

    private companion object {
        const val OWNER = 1L
        const val EXCLUDED = "Excluded group"

        // The default turns both duplicate modes on; a write to the in-memory store is silent, so off is seeded.
        fun libraryPreferences(markDuplicates: Boolean) = LibraryPreferences(
            if (markDuplicates) {
                InMemoryPreferenceStore()
            } else {
                InMemoryPreferenceStore(
                    sequenceOf(InMemoryPreference("mark_duplicate_read_chapter_read", emptySet<String>(), emptySet())),
                )
            },
        )
    }
}
