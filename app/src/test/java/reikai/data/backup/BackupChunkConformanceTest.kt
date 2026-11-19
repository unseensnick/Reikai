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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToHexString
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterCopyMarkRepositoryImpl
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.chapter.VolumePickRepositoryImpl
import reikai.data.library.LibraryManualOrderRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelTrackRepositoryImpl
import reikai.data.readinglog.ReadingLogRepositoryImpl
import reikai.data.readinglog.ReadingPaceRepositoryImpl
import reikai.domain.chapter.ChapterCopyMark
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryHiddenRepository
import reikai.domain.library.ManualOrderPosition
import reikai.domain.local.VolumeOwner
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.readinglog.ReadingPaceRepository
import reikai.novel.local.LocalNovelSource
import reikai.util.testAppScope
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

/**
 * A chunk reads its rows for all its series at once, so a row has to land on the series it belongs to.
 * Two series backed up in one chunk come out exactly as each does in a chunk of its own, for both types,
 * over the real queries, each series holding every kind of row a chunk reads: kept states, duplicate
 * marks, Manual places and, for a local series, its volume pick among them.
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupChunkConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: Store

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        store = Store(DatabaseBindings.providesDatabase(driver))
        listOf(
            "INSERT INTO category(id, name, `order`, flags, content_type) VALUES (1, 'A', 1, 0, 0), (2, 'B', 2, 0, 0)",
        ).forEach { driver.execute(null, it, 0).await() }
    }

    @AfterEach
    fun tearDown() {
        store.scope.cancel()
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chunk writes each series what a chunk of its own writes`(type: Type) = runTest {
        type.seed(driver, store)
        val options = BackupOptions(customInfo = false)

        val together = type.backUp(options, store, listOf(1L, 2L))
        val apart = type.backUp(options, store, listOf(1L)) + type.backUp(options, store, listOf(2L))

        together shouldBe apart
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chunk of local series writes each what a chunk of its own writes`(type: Type) = runTest {
        type.seed(driver, store)
        val options = BackupOptions(customInfo = false)

        val together = type.backUp(options, store, listOf(1L, 2L), local = true)
        val apart = type.backUp(options, store, listOf(1L), local = true) +
            type.backUp(options, store, listOf(2L), local = true)

        together shouldBe apart
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chunk's order moves no row to another series`(type: Type) = runTest {
        type.seed(driver, store)

        type.backUp(BackupOptions(customInfo = false), store, listOf(1L, 2L)) shouldBe
            type.backUp(BackupOptions(customInfo = false), store, listOf(2L, 1L)).reversed()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `each series in a chunk carries its own reading sessions`(type: Type) = runTest {
        type.seed(driver, store)

        type.sessions(store, listOf(1L, 2L)) shouldBe
            listOf(listOf(Triple("/a1", 1000L, 5L)), listOf(Triple("/b1", 2000L, 6L)))
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `each series in a chunk carries its own history`(type: Type) = runTest {
        type.seed(driver, store)

        type.history(store, listOf(1L, 2L)) shouldBe
            listOf(listOf(Triple("/a1", 1000L, 5L)), listOf(Triple("/b1", 2000L, 6L)))
    }

    /** A local series' grouping describes the series, not its chapters, so a backup without Chapters keeps it. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a local series' grouping is backed up with Chapters unticked`(type: Type) = runTest {
        type.seed(driver, store)

        type.ownersWithoutChapters(store) shouldBe listOf(listOf("B"), emptyList())
    }

    // Manga history keys by chapter url and novel history takes its read-aloud times from the pace rows, both
    // read for the chapters part already.
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chunk reads the rows its chapters and history share once`(type: Type) = runTest {
        type.seed(driver, store)

        type.sharedReads(store, listOf(1L, 2L)) shouldBe 1
    }

    /** The database and the repositories over it whose rows a chunk reads for every series at once. */
    class Store(val database: Database) {
        val scope = testAppScope()
        val tombstones = ChapterTombstoneRepositoryImpl(database)
        val copyMarks = ChapterCopyMarkRepositoryImpl(database, scope)
        val manualOrder = LibraryManualOrderRepositoryImpl(database)
        val volumePicks = VolumePickRepositoryImpl(database, scope)
    }

    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA) {
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
                "INSERT INTO manga_chapter_tombstone(manga_id, url, read, bookmark, last_page_read, page_count, " +
                    "date_fetch, history_read_at, history_read_duration, removed_at) VALUES " +
                    "(1, '/a9', 1, 0, 3, 10, 0, 1500, 4, 0), (2, '/b9', 0, 1, 7, 20, 0, NULL, NULL, 0)",
            )

            override suspend fun backUp(options: BackupOptions, store: Store, ids: List<Long>, local: Boolean) =
                chunk(options, store, ids, local).map { ProtoBuf.encodeToHexString(BackupManga.serializer(), it) }

            override suspend fun sessions(store: Store, ids: List<Long>) =
                chunk(BackupOptions(customInfo = false), store, ids).map { it.readingSessions.asTriples() }

            override suspend fun history(store: Store, ids: List<Long>) =
                chunk(BackupOptions(customInfo = false), store, ids).map { backup ->
                    backup.history.map { Triple(it.url, it.lastRead, it.readDuration) }
                }

            override suspend fun ownersWithoutChapters(store: Store) =
                chunk(BackupOptions(chapters = false, customInfo = false), store, listOf(1L, 2L), local = true)
                    .map { backup -> backup.volumeOwners.map { it.folder } }

            override suspend fun sharedReads(store: Store, ids: List<Long>): Int {
                var reads = 0
                chunk(BackupOptions(customInfo = false), store, ids) { reads++ }
                return reads
            }

            private suspend fun chunk(
                options: BackupOptions,
                store: Store,
                ids: List<Long>,
                local: Boolean = false,
                onSharedRead: () -> Unit = {},
            ): List<BackupManga> {
                val database = store.database
                val chapters = ChapterRepositoryImpl(database)
                val creator = MangaBackupCreator(
                    libraryHides = FakeLibraryHiddenRepository(),
                    tombstones = store.tombstones,
                    manualOrder = store.manualOrder,
                    mangaRepository = MangaRepositoryImpl(database),
                    chapterRepository = object : ChapterRepository by chapters {
                        override suspend fun getChaptersByMangaIds(mangaIds: List<Long>) =
                            chapters.getChaptersByMangaIds(mangaIds).also { onSharedRead() }
                    },
                    categoryRepository = CategoryRepositoryImpl(database),
                    historyRepository = HistoryRepositoryImpl(database),
                    trackRepository = TrackRepositoryImpl(database),
                    mangaMetadataRepository = mockk { coEvery { getMetadataById(any()) } returns null },
                    customMangaInfoRepository = mockk(),
                    getFavorites = mockk(),
                    getManga = mockk(),
                    mergeGroupRepository = mockk(),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                    copyMarks = store.copyMarks,
                    localVolumes = if (local) grouped else NoLocalVolumes,
                    volumePicks = store.volumePicks,
                    readingLog = ReadingLogRepositoryImpl(database),
                )
                val source = if (local) LocalSource.ID else 1L
                return options.backupChunk(
                    ids.map {
                        Manga.create().copy(id = it, url = "u$it", source = source)
                    },
                    creator,
                )
            }
        },
        NOVELS(ContentType.NOVELS) {
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
                "INSERT INTO novel_chapter_tombstone(novel_id, url, read, bookmark, last_text_progress, words, " +
                    "listened_ms, date_fetch, history_last_read, history_time_read, removed_at) VALUES " +
                    "(1, '/a9', 1, 0, 30, 900, 0, 0, 1500, 4, 0), (2, '/b9', 0, 1, 70, NULL, NULL, 0, NULL, NULL, 0)",
            )

            override suspend fun backUp(options: BackupOptions, store: Store, ids: List<Long>, local: Boolean) =
                chunk(options, store, ids, local).map { ProtoBuf.encodeToHexString(BackupNovel.serializer(), it) }

            override suspend fun sessions(store: Store, ids: List<Long>) =
                chunk(BackupOptions(customInfo = false), store, ids).map { it.readingSessions.asTriples() }

            override suspend fun history(store: Store, ids: List<Long>) =
                chunk(BackupOptions(customInfo = false), store, ids).map { backup ->
                    backup.history.map { Triple(it.url, it.lastRead, it.readDuration) }
                }

            override suspend fun ownersWithoutChapters(store: Store) =
                chunk(BackupOptions(chapters = false, customInfo = false), store, listOf(1L, 2L), local = true)
                    .map { backup -> backup.volumeOwners.map { it.folder } }

            override suspend fun sharedReads(store: Store, ids: List<Long>): Int {
                var reads = 0
                chunk(BackupOptions(customInfo = false), store, ids) { reads++ }
                return reads
            }

            private suspend fun chunk(
                options: BackupOptions,
                store: Store,
                ids: List<Long>,
                local: Boolean = false,
                onSharedRead: () -> Unit = {},
            ): List<BackupNovel> {
                val database = store.database
                val pace = ReadingPaceRepositoryImpl(database)
                val creator = NovelBackupCreator(
                    libraryHides = FakeLibraryHiddenRepository(),
                    tombstones = store.tombstones,
                    manualOrder = store.manualOrder,
                    novelRepository = mockk(),
                    novelChapterRepository = NovelChapterRepositoryImpl(database),
                    categoryRepository = CategoryRepositoryImpl(database),
                    novelTrackRepository = NovelTrackRepositoryImpl(database),
                    mergeGroupRepository = mockk(),
                    customNovelInfoRepository = mockk(),
                    novelHistoryRepository = NovelHistoryRepositoryImpl(database),
                    novelSourceManager = mockk(),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                    copyMarks = store.copyMarks,
                    localVolumes = if (local) grouped else NoLocalVolumes,
                    volumePicks = store.volumePicks,
                    readingLog = ReadingLogRepositoryImpl(database),
                    readingPace = object : ReadingPaceRepository by pace {
                        override suspend fun novelChapterPace(novelIds: List<Long>) =
                            pace.novelChapterPace(novelIds).also { onSharedRead() }
                    },
                    localNovelLanguages = NoLocalNovelLanguages,
                )
                return options.backupChunk(
                    ids.map {
                        Novel.create().copy(id = it, url = "u$it", source = if (local) LocalNovelSource.ID else "src")
                    },
                    creator,
                )
            }
        },
        ;

        abstract val statements: List<String>

        /** Every row kind for both series: through SQL, and through the repositories where they own the encoding. */
        suspend fun seed(driver: JdbcSqliteDriver, store: Store) {
            statements.forEach { driver.execute(null, it, 0).await() }
            store.copyMarks.set(
                contentType,
                listOf(ChapterCopyMark(1L, "/a1", CopyMark.NeverSkip), ChapterCopyMark(2L, "/b1", CopyMark.Group(4L))),
            )
            store.manualOrder.upsert(
                listOf(ManualOrderPosition(contentType, 1L, 1L, 5L), ManualOrderPosition(contentType, 2L, 2L, 7L)),
            )
            store.volumePicks.set(contentType, 1L, "v1")
            store.volumePicks.set(contentType, 2L, "v2")
        }

        /** Each series' backup on the wire, so two are compared field by field without a data class. */
        abstract suspend fun backUp(
            options: BackupOptions,
            store: Store,
            ids: List<Long>,
            local: Boolean = false,
        ): List<String>

        /** Each series' reading sessions as (url, endedAt, duration), since the backup class has no equals. */
        abstract suspend fun sessions(store: Store, ids: List<Long>): List<List<Triple<String, Long, Long>>>

        /** Each series' history as (url, lastRead, readDuration). */
        abstract suspend fun history(store: Store, ids: List<Long>): List<List<Triple<String, Long, Long>>>

        /** How many times one chunk ran the read its chapters and history parts share. */
        abstract suspend fun sharedReads(store: Store, ids: List<Long>): Int

        /** The folders each local series' backup says its group holds, from a backup made with Chapters unticked. */
        abstract suspend fun ownersWithoutChapters(store: Store): List<List<String>>

        protected fun List<BackupReadingSession>.asTriples() = map { Triple(it.url, it.endedAt, it.duration) }

        // Series u1's group holds folder B whole.
        protected val grouped = object : LocalVolumeRepository by NoLocalVolumes {
            override suspend fun all(type: ContentType) = listOf(VolumeOwner("B", VolumeOwner.WHOLE, "u1"))
        }
    }
}
