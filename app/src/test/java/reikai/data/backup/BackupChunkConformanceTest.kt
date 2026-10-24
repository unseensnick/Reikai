package reikai.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupReadingSession
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToHexString
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelTrackRepositoryImpl
import reikai.data.readinglog.ReadingLogRepositoryImpl
import reikai.domain.novel.model.Novel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * A chunk reads its rows for all its series at once, so a row has to land on the series it belongs to.
 * Two series backed up in one chunk come out exactly as each does in a chunk of its own, for both types,
 * over the real queries.
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupChunkConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
        listOf(
            "INSERT INTO category(id, name, `order`, flags, content_type) VALUES (1, 'A', 1, 0, 0), (2, 'B', 2, 0, 0)",
        ).forEach { driver.execute(null, it, 0).await() }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chunk writes each series what a chunk of its own writes`(type: Type) = runTest {
        type.seed(driver)
        val options = BackupOptions(customInfo = false)

        val together = type.backUp(options, database, listOf(1L, 2L))
        val apart = type.backUp(options, database, listOf(1L)) + type.backUp(options, database, listOf(2L))

        together shouldBe apart
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chunk's order moves no row to another series`(type: Type) = runTest {
        type.seed(driver)

        type.backUp(BackupOptions(customInfo = false), database, listOf(1L, 2L)) shouldBe
            type.backUp(BackupOptions(customInfo = false), database, listOf(2L, 1L)).reversed()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `each series in a chunk carries its own reading sessions`(type: Type) = runTest {
        type.seed(driver)

        type.sessions(database, listOf(1L, 2L)) shouldBe
            listOf(listOf(Triple("/a1", 1000L, 5L)), listOf(Triple("/b1", 2000L, 6L)))
    }

    enum class Type {
        MANGA {
            override val statements = listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(1, 1, 'u1', 'T1', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), " +
                    "(2, 1, 'u2', 'T2', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES " +
                    "(10, 1, '/a1', 'A1', NULL, 1, 0, 0, 1.0, 1, 0, 0, '{}'), " +
                    "(11, 2, '/b1', 'B1', NULL, 1, 0, 0, 1.0, 0, 0, 0, '{}'), " +
                    "(12, 1, '/a2', 'A2', NULL, 0, 0, 0, 2.0, 0, 0, 0, '{}')",
                "INSERT INTO manga_category(manga_id, category_id) VALUES (1, 1), (2, 2)",
                "INSERT INTO manga_track(id, manga_id, tracker_id, remote_id, remote_url, title, " +
                    "last_chapter_read, total_chapters, status, score) VALUES " +
                    "(1, 1, 1, 100, '', 'T1', 1.0, 0, 1, 0.0), (2, 2, 1, 200, '', 'T2', 1.0, 0, 1, 0.0)",
                "INSERT INTO history(id, manga_id, chapter_id, read_at, read_duration) VALUES " +
                    "(1, 1, 10, 1000, 5), (2, 2, 11, 2000, 6)",
                "INSERT INTO reading_session(chapter_id, ended_at, duration) VALUES (10, 1000, 5), (11, 2000, 6)",
                "INSERT INTO manga_chapter_number_override(manga_id, url, number, source_number) VALUES " +
                    "(1, '/a1', 1.5, 1.0), (2, '/b1', 2.5, 7.0)",
                "INSERT INTO excluded_scanlator(manga_id, scanlator) VALUES (1, 'X'), (2, 'Y')",
            )

            override suspend fun backUp(options: BackupOptions, database: Database, ids: List<Long>) =
                chunk(options, database, ids).map { ProtoBuf.encodeToHexString(BackupManga.serializer(), it) }

            override suspend fun sessions(database: Database, ids: List<Long>) =
                chunk(BackupOptions(customInfo = false), database, ids).map { it.readingSessions.asTriples() }

            private suspend fun chunk(options: BackupOptions, database: Database, ids: List<Long>): List<BackupManga> {
                val creator = MangaBackupCreator(
                    mangaRepository = MangaRepositoryImpl(database),
                    chapterRepository = ChapterRepositoryImpl(database),
                    categoryRepository = CategoryRepositoryImpl(database),
                    historyRepository = HistoryRepositoryImpl(database),
                    trackRepository = TrackRepositoryImpl(database),
                    mangaMetadataRepository = mockk { coEvery { getMetadataById(any()) } returns null },
                    customMangaInfoRepository = mockk(),
                    getFavorites = mockk(),
                    getManga = mockk(),
                    mergeGroupRepository = mockk(),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                    readingLog = ReadingLogRepositoryImpl(database),
                )
                return options.backupChunk(ids.map { Manga.create().copy(id = it, url = "u$it", source = 1L) }, creator)
            }
        },
        NOVELS {
            override val statements = listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 'src', 'u1', 'T1', 0, 0, 0, 0), (2, 'src', 'u2', 'T2', 0, 0, 0, 0)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "(10, 1, '/a1', 'A1', 1, 0, 0, 1.0, 1, 0, 0), " +
                    "(11, 2, '/b1', 'B1', 1, 0, 0, 1.0, 0, 0, 0), " +
                    "(12, 1, '/a2', 'A2', 0, 0, 0, 2.0, 0, 0, 0)",
                "INSERT INTO novels_categories(novel_id, category_id) VALUES (1, 1), (2, 2)",
                "INSERT INTO novel_tracks(_id, novel_id, sync_id, remote_id, title, last_chapter_read, " +
                    "total_chapters, status, score, remote_url) VALUES " +
                    "(1, 1, 1, 100, 'T1', 1.0, 0, 1, 0.0, ''), (2, 2, 1, 200, 'T2', 1.0, 0, 1, 0.0, '')",
                "INSERT INTO novel_history(chapter_id, last_read, time_read) VALUES (10, 1000, 5), (11, 2000, 6)",
                "INSERT INTO novel_reading_session(chapter_id, ended_at, duration) VALUES (10, 1000, 5), (11, 2000, 6)",
                "INSERT INTO novel_chapter_number_override(novel_id, url, number, source_number) VALUES " +
                    "(1, '/a1', 1.5, 1.0), (2, '/b1', 2.5, 7.0)",
            )

            override suspend fun backUp(options: BackupOptions, database: Database, ids: List<Long>) =
                chunk(options, database, ids).map { ProtoBuf.encodeToHexString(BackupNovel.serializer(), it) }

            override suspend fun sessions(database: Database, ids: List<Long>) =
                chunk(BackupOptions(customInfo = false), database, ids).map { it.readingSessions.asTriples() }

            private suspend fun chunk(options: BackupOptions, database: Database, ids: List<Long>): List<BackupNovel> {
                val creator = NovelBackupCreator(
                    novelRepository = mockk(),
                    novelChapterRepository = NovelChapterRepositoryImpl(database),
                    categoryRepository = CategoryRepositoryImpl(database),
                    novelTrackRepository = NovelTrackRepositoryImpl(database),
                    mergeGroupRepository = mockk(),
                    customNovelInfoRepository = mockk(),
                    novelHistoryRepository = NovelHistoryRepositoryImpl(database),
                    novelSourceManager = mockk(),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                    readingLog = ReadingLogRepositoryImpl(database),
                )
                return options.backupChunk(
                    ids.map {
                        Novel.create().copy(id = it, url = "u$it", source = "src")
                    },
                    creator,
                )
            }
        },
        ;

        abstract val statements: List<String>

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        /** Each series' backup on the wire, so two are compared field by field without a data class. */
        abstract suspend fun backUp(options: BackupOptions, database: Database, ids: List<Long>): List<String>

        /** Each series' reading sessions as (url, endedAt, duration), since the backup class has no equals. */
        abstract suspend fun sessions(database: Database, ids: List<Long>): List<List<Triple<String, Long, Long>>>

        protected fun List<BackupReadingSession>.asTriples() = map { Triple(it.url, it.endedAt, it.duration) }
    }
}
