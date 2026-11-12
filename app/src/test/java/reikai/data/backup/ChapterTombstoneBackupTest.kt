package reikai.data.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.mangaRestorer
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupChapterTombstone
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupTombstoneSession
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * A removed local chapter's kept reading state travels in a backup, for both types: the backup carries it
 * whole with its sessions, a restore brings it back, and a restored chapter meets the state waiting for it
 * on the device. The fold a restore applies is ChapterTombstoneFoldConformanceTest's.
 */
class ChapterTombstoneBackupTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() = driver.close()

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a backup carries a kept state with its history and sessions`(type: Type) = runTest {
        type.seed(driver)
        type.tombstone(driver)

        val carried = type.backedUp(database, ChapterTombstoneRepositoryImpl(database)).tombstones.map {
            Triple(it.url, it.historyReadAt to it.historyDuration, it.sessions.map { s -> s.endedAt })
        }

        carried shouldBe listOf(Triple("/c5", 200L to 90L, listOf(2_000L)))
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a backup without History carries a kept state without its history, sessions or listened time`(type: Type) =
        runTest {
            type.seed(driver)
            type.tombstone(driver)

            val carried = type.backedUp(database, ChapterTombstoneRepositoryImpl(database), history = false)
                .tombstones.map {
                    Triple(it.url, listOf(it.historyReadAt, it.historyDuration, it.listenedMs), it.sessions)
                }

            carried shouldBe listOf(Triple("/c5", listOf(0L, null, null), emptyList()))
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a backup without Chapters carries no kept state, even with History`(type: Type) = runTest {
        type.seed(driver)
        type.tombstone(driver)

        type.backedUp(database, ChapterTombstoneRepositoryImpl(database), chapters = false).tombstones shouldBe
            emptyList()
    }

    /** The creator reads chapters and kept states in two queries; a refresh can capture a chapter between them. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter captured between the backup's two reads is still in the backup`(type: Type) = runTest {
        type.seed(driver)
        type.restore(database, tombstones = emptyList(), chapters = listOf("/c5"))
        val real = ChapterTombstoneRepositoryImpl(database)
        val capturingAfterItsRead = object : ChapterTombstoneRepository by real {
            override suspend fun getByOwners(contentType: ContentType, ownerIds: List<Long>) =
                real.getByOwners(contentType, ownerIds).also {
                    type.tombstone(driver)
                    driver.exec(type.deleteChapterStatement)
                }
        }

        val backed = type.backedUp(database, capturingAfterItsRead)

        (backed.chapterUrls + backed.tombstones.map { it.url }).contains("/c5") shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restore brings a kept state back with its sessions`(type: Type) = runTest {
        type.seed(driver)

        type.restore(database, tombstones = listOf(TOMBSTONE), chapters = emptyList())

        (type.tombstoneUrls(driver) to type.sessionCount(driver)) shouldBe (listOf("/c5") to 1L)
    }

    /** A kept state's sessions come from a file, so they pass the reading log's own check, as restored sessions do. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restore drops a kept state's sessions the reading log would not keep`(type: Type) = runTest {
        type.seed(driver)
        val corrupt = BackupChapterTombstone(
            url = "/c5",
            chapterNumber = 5.0,
            sessions = listOf(
                BackupTombstoneSession(2_000, 40),
                BackupTombstoneSession(3_000, -1),
                BackupTombstoneSession(4_000, Long.MAX_VALUE),
                BackupTombstoneSession(Long.MAX_VALUE, 40),
            ),
        )

        type.restore(database, tombstones = listOf(corrupt), chapters = emptyList())

        type.sessionCount(driver) shouldBe 1L
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restored chapter spends the kept state waiting at its url`(type: Type) = runTest {
        type.seed(driver)
        type.tombstone(driver)

        type.restore(database, tombstones = emptyList(), chapters = listOf("/c5"))

        (type.readUrls(driver) to type.tombstoneUrls(driver)) shouldBe (listOf("/c5") to emptyList())
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restored kept state lands on the row the device holds at its url`(type: Type) = runTest {
        type.seed(driver)
        type.restore(database, tombstones = emptyList(), chapters = listOf("/c5"))

        type.restore(database, tombstones = listOf(TOMBSTONE), chapters = emptyList())

        (type.readUrls(driver) to type.tombstoneUrls(driver)) shouldBe (listOf("/c5") to emptyList())
    }

    @Serializable
    class OlderBackupManga(@ProtoNumber(1) var source: Long, @ProtoNumber(2) var url: String)

    @Serializable
    class OlderBackupNovel(@ProtoNumber(1) var source: String, @ProtoNumber(2) var url: String)

    @Test
    fun `an older reader skips the kept states`() {
        val manga = ProtoBuf.encodeToByteArray(
            BackupManga.serializer(),
            BackupManga(source = 1L, url = "u", chapterTombstones = listOf(TOMBSTONE)),
        )
        val novel = ProtoBuf.encodeToByteArray(
            BackupNovel.serializer(),
            BackupNovel(source = "src", url = "u", chapterTombstones = listOf(TOMBSTONE)),
        )

        listOf(
            ProtoBuf.decodeFromByteArray(OlderBackupManga.serializer(), manga).url,
            ProtoBuf.decodeFromByteArray(OlderBackupNovel.serializer(), novel).url,
        ) shouldBe listOf("u", "u")
    }

    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA) {
            override val seedStatement =
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(1, 1, 'u', 'T', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')"
            override val tombstoneStatements = listOf(
                "INSERT INTO manga_chapter_tombstone(manga_id, url, chapter_number, read, bookmark, last_page_read, " +
                    "page_count, date_fetch, history_read_at, history_read_duration, removed_at) " +
                    "VALUES (1, '/c5', 5.0, 1, 0, 3, 20, 500, 200, 90, 7)",
                "INSERT INTO manga_chapter_tombstone_session(manga_id, url, ended_at, duration) " +
                    "VALUES (1, '/c5', 2000, 40)",
            )
            override val tombstoneUrlQuery = "SELECT url FROM manga_chapter_tombstone ORDER BY url"
            override val sessionCountQuery = "SELECT count(*) FROM manga_chapter_tombstone_session"
            override val readUrlQuery = "SELECT remote_url FROM chapter WHERE user_read = 1 ORDER BY remote_url"

            override val deleteChapterStatement = "DELETE FROM chapter WHERE remote_url = '/c5'"

            override suspend fun backedUp(
                database: Database,
                tombstones: ChapterTombstoneRepository,
                chapters: Boolean,
                history: Boolean,
            ) =
                BackupManga(source = 1L, url = "u").also { backup ->
                    val batch = listOf(Manga.create().copy(id = 1) to backup)
                    MangaBackupCreator(
                        manualOrder = FakeLibraryManualOrderRepository(),
                        mangaRepository = mockk(),
                        chapterRepository = ChapterRepositoryImpl(database),
                        trackRepository = mockk(),
                        categoryRepository = mockk(),
                        historyRepository = HistoryRepositoryImpl(database),
                        mangaMetadataRepository = mockk(),
                        customMangaInfoRepository = mockk(),
                        getFavorites = mockk(),
                        getManga = mockk(),
                        mergeGroupRepository = mockk(),
                        chapterNumberOverrides = NoChapterNumberOverrides,
                        copyMarks = InMemoryChapterCopyMarks(),
                        localVolumes = NoLocalVolumes,
                        readingLog = NoReadingLog,
                        tombstones = tombstones,
                    ).run {
                        if (chapters) chapters(batch)
                        if (history) history(batch)
                    }
                }.let { Backed(it.chapters.map { chapter -> chapter.url }, it.chapterTombstones) }

            override suspend fun restore(
                database: Database,
                tombstones: List<BackupChapterTombstone>,
                chapters: List<String>,
            ) {
                mangaRestorer(database).restore(
                    listOf(
                        BackupManga(
                            source = 1L,
                            url = "u",
                            title = "T",
                            chapters = chapters.map { BackupChapter(url = it, name = "C", chapterNumber = 5f) },
                            chapterTombstones = tombstones,
                        ),
                    ),
                    emptyList(),
                )
            }
        },
        NOVEL(ContentType.NOVELS) {
            override val seedStatement =
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 'src', 'u', 'T', 0, 0, 0, 0)"
            override val tombstoneStatements = listOf(
                "INSERT INTO novel_chapter_tombstone(novel_id, url, chapter_number, read, bookmark, " +
                    "last_text_progress, words, listened_ms, date_fetch, history_last_read, history_time_read, " +
                    "removed_at) " +
                    "VALUES (1, '/c5', 5.0, 1, 0, 3, 20, 5, 500, 200, 90, 7)",
                "INSERT INTO novel_chapter_tombstone_session(novel_id, url, ended_at, duration) " +
                    "VALUES (1, '/c5', 2000, 40)",
            )
            override val tombstoneUrlQuery = "SELECT url FROM novel_chapter_tombstone ORDER BY url"
            override val sessionCountQuery = "SELECT count(*) FROM novel_chapter_tombstone_session"
            override val readUrlQuery = "SELECT url FROM novel_chapters WHERE read = 1 ORDER BY url"

            override val deleteChapterStatement = "DELETE FROM novel_chapters WHERE url = '/c5'"

            override suspend fun backedUp(
                database: Database,
                tombstones: ChapterTombstoneRepository,
                chapters: Boolean,
                history: Boolean,
            ) =
                BackupNovel(source = "src", url = "u").also { backup ->
                    val batch = listOf(Novel.create().copy(id = 1) to backup)
                    NovelBackupCreator(
                        manualOrder = FakeLibraryManualOrderRepository(),
                        novelRepository = mockk(),
                        novelChapterRepository = NovelChapterRepositoryImpl(database),
                        categoryRepository = mockk(),
                        novelTrackRepository = mockk(),
                        mergeGroupRepository = mockk(),
                        customNovelInfoRepository = mockk(),
                        novelHistoryRepository = NovelHistoryRepositoryImpl(database),
                        novelSourceManager = mockk(),
                        chapterNumberOverrides = NoChapterNumberOverrides,
                        copyMarks = InMemoryChapterCopyMarks(),
                        localVolumes = NoLocalVolumes,
                        readingLog = NoReadingLog,
                        readingPace = NoReadingPace,
                        localNovelLanguages = NoLocalNovelLanguages,
                        tombstones = tombstones,
                    ).run {
                        if (chapters) chapters(batch)
                        if (history) history(batch)
                    }
                }.let { Backed(it.chapters.map { chapter -> chapter.url }, it.chapterTombstones) }

            override suspend fun restore(
                database: Database,
                tombstones: List<BackupChapterTombstone>,
                chapters: List<String>,
            ) {
                NovelRestorer(
                    manualOrder = FakeLibraryManualOrderRepository(),
                    novelRepository = NovelRepositoryImpl(database),
                    novelChapterRepository = NovelChapterRepositoryImpl(database),
                    categoryRepository = mockk(relaxed = true),
                    novelTrackRepository = mockk(relaxed = true),
                    restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
                    setCustomNovelInfo = mockk(relaxed = true),
                    novelHistoryRepository = NovelHistoryRepositoryImpl(database),
                    chapterNumberOverrides = NoChapterNumberOverrides,
                    copyMarks = InMemoryChapterCopyMarks(),
                    localVolumes = NoLocalVolumes,
                    readingLog = NoReadingLog,
                    readingPace = NoReadingPace,
                    localNovelLanguages = NoLocalNovelLanguages,
                    tombstones = ChapterTombstoneRepositoryImpl(database),
                ).restore(
                    BackupNovel(
                        source = "src",
                        url = "u",
                        title = "T",
                        chapters = chapters.map { BackupNovelChapter(url = it, name = "C", chapterNumber = 5.0) },
                        chapterTombstones = tombstones,
                    ),
                    emptyList(),
                )
            }
        },
        ;

        abstract val seedStatement: String
        abstract val tombstoneStatements: List<String>
        abstract val tombstoneUrlQuery: String
        abstract val sessionCountQuery: String
        abstract val readUrlQuery: String

        /**
         * The owner's chapters and kept states as the type's creator writes them, reading tombstones from [tombstones].
         * [chapters] and [history] are the backup's two options, whose parts run in that order.
         */
        abstract suspend fun backedUp(
            database: Database,
            tombstones: ChapterTombstoneRepository,
            chapters: Boolean = true,
            history: Boolean = true,
        ): Backed

        /** The row at /c5 removed, as a refresh removes a chapter whose file is gone. */
        abstract val deleteChapterStatement: String

        /** What a creator wrote for the owner: its chapters' urls and its kept states. */
        class Backed(val chapterUrls: List<String>, val tombstones: List<BackupChapterTombstone>)

        /** A restore of the backup's [tombstones] and unread [chapters] onto this database. */
        abstract suspend fun restore(
            database: Database,
            tombstones: List<BackupChapterTombstone>,
            chapters: List<String>,
        )

        suspend fun seed(driver: JdbcSqliteDriver) = driver.exec(seedStatement)

        suspend fun tombstone(driver: JdbcSqliteDriver) = tombstoneStatements.forEach { driver.exec(it) }

        suspend fun tombstoneUrls(driver: JdbcSqliteDriver) = driver.rows(tombstoneUrlQuery) { it.getString(0)!! }

        suspend fun sessionCount(driver: JdbcSqliteDriver) = driver.rows(sessionCountQuery) { it.getLong(0)!! }.single()

        suspend fun readUrls(driver: JdbcSqliteDriver) = driver.rows(readUrlQuery) { it.getString(0)!! }
    }

    companion object {
        private val TOMBSTONE = BackupChapterTombstone(
            url = "/c5",
            chapterNumber = 5.0,
            read = true,
            progress = 3,
            length = 20,
            dateFetch = 500,
            historyReadAt = 200,
            historyDuration = 90,
            sessions = listOf(BackupTombstoneSession(2_000, 40)),
            removedAt = 7,
        )

        private suspend fun JdbcSqliteDriver.exec(sql: String) {
            execute(null, sql, 0).await()
        }

        private suspend fun <T> JdbcSqliteDriver.rows(sql: String, map: (SqlCursor) -> T): List<T> = executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(map(cursor)) }) },
            0,
        ).await()
    }
}
