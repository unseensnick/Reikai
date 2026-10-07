package reikai.data.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.mangaRestorer
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
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
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.chapter.ChapterNumberOverride
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ContentType
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.model.Novel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * A corrected chapter number travels in a backup as the corrected number plus the source's own, for
 * both types: the backup writes both, and a restore onto a device still on the source's number brings
 * the correction back, so a later clear still finds the source's number.
 */
class ChapterNumberOverrideBackupTest {

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

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a backup carries the correction and the source's number`(type: Type) = runTest {
        type.seed(driver)
        overrides.set(type.contentType, 1, "/c5", 6.0)

        type.backedUp(database, overrides) shouldBe (6.0 to 5.0)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restore puts the correction on the device's row`(type: Type) = runTest {
        type.seed(driver)

        type.restore(database)

        type.number(driver) shouldBe 6.0
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restore keeps the source's number for a later clear`(type: Type) = runTest {
        type.seed(driver)

        type.restore(database)

        overrides.getByOwner(type.contentType, 1).values.toList() shouldBe
            listOf(ChapterNumberOverride("/c5", 6.0, 5.0))
    }

    /** The fields an app that predates the source number reads, so it skips the new one. */
    @Serializable
    class OlderBackupChapter(@ProtoNumber(1) var url: String, @ProtoNumber(2) var name: String)

    @Test
    fun `an older reader skips the source's number`() {
        val bytes = ProtoBuf.encodeToByteArray(
            BackupChapter.serializer(),
            BackupChapter(url = "/c5", name = "C", chapterNumber = 6f, sourceChapterNumber = 5.0),
        )

        ProtoBuf.decodeFromByteArray(OlderBackupChapter.serializer(), bytes).url shouldBe "/c5"
    }

    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA) {
            override val statements = listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "(1, 1, 'u', 'T', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES (1, 1, '/c5', 'C', NULL, 0, 0, 0, 5.0, 0, 0, 0, '{}')",
            )
            override val numberQuery = "SELECT remote_chapter_number FROM chapter WHERE id = 1"

            override suspend fun backedUp(database: Database, overrides: ChapterNumberOverrideRepositoryImpl) =
                BackupManga(source = 1L, url = "u").also { backup ->
                    MangaBackupCreator(
                        mangaRepository = mockk(),
                        chapterRepository = ChapterRepositoryImpl(database),
                        trackRepository = mockk(),
                        getCategories = mockk(),
                        getHistory = mockk(),
                        mangaMetadataRepository = mockk(),
                        customMangaInfoRepository = mockk(),
                        getFavorites = mockk(),
                        getManga = mockk(),
                        mergeGroupRepository = mockk(),
                        chapterNumberOverrides = overrides,
                    ).chapters(Manga.create().copy(id = 1), backup)
                }.chapters.single().let { it.chapterNumber.toDouble() to it.sourceChapterNumber }

            override suspend fun restore(database: Database) {
                mangaRestorer(database).restore(
                    listOf(
                        BackupManga(
                            source = 1L,
                            url = "u",
                            title = "T",
                            chapters = listOf(
                                BackupChapter(url = "/c5", name = "C", chapterNumber = 6f, sourceChapterNumber = 5.0),
                            ),
                        ),
                    ),
                    emptyList(),
                )
            }
        },
        NOVEL(ContentType.NOVELS) {
            override val statements = listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 'src', 'u', 'T', 0, 0, 0, 0)",
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES (1, 1, '/c5', 'C', 0, 0, 0, 5.0, 0, 0, 0)",
            )
            override val numberQuery = "SELECT chapter_number FROM novel_chapters WHERE _id = 1"

            override suspend fun backedUp(database: Database, overrides: ChapterNumberOverrideRepositoryImpl) =
                BackupNovel(source = "src", url = "u").also { backup ->
                    NovelBackupCreator(
                        novelRepository = mockk(),
                        novelChapterRepository = NovelChapterRepositoryImpl(database),
                        categoryRepository = mockk(),
                        novelTrackRepository = mockk(),
                        mergeGroupRepository = mockk(),
                        customNovelInfoRepository = mockk(),
                        novelHistoryRepository = mockk(),
                        novelSourceManager = mockk(),
                        chapterNumberOverrides = overrides,
                    ).chapters(Novel.create().copy(id = 1), backup)
                }.chapters.single().let { it.chapterNumber to it.sourceChapterNumber }

            override suspend fun restore(database: Database) {
                NovelRestorer(
                    novelRepository = NovelRepositoryImpl(database),
                    novelChapterRepository = NovelChapterRepositoryImpl(database),
                    categoryRepository = mockk(relaxed = true),
                    novelTrackRepository = mockk(relaxed = true),
                    restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
                    setCustomNovelInfo = mockk(relaxed = true),
                    novelHistoryRepository = NovelHistoryRepositoryImpl(database),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                ).restore(
                    BackupNovel(
                        source = "src",
                        url = "u",
                        title = "T",
                        chapters = listOf(
                            BackupNovelChapter(url = "/c5", name = "C", chapterNumber = 6.0, sourceChapterNumber = 5.0),
                        ),
                    ),
                    emptyList(),
                )
            }
        },
        ;

        abstract val statements: List<String>
        abstract val numberQuery: String

        /** The backup's one chapter as the type's creator writes it: its number and the source's. */
        abstract suspend fun backedUp(
            database: Database,
            overrides: ChapterNumberOverrideRepositoryImpl,
        ): Pair<Double, Double?>

        abstract suspend fun restore(database: Database)

        suspend fun seed(driver: JdbcSqliteDriver) = statements.forEach { driver.execute(null, it, 0).await() }

        suspend fun number(driver: JdbcSqliteDriver): Double = driver.executeQuery(
            null,
            numberQuery,
            { cursor -> QueryResult.Value(cursor.next().value.let { cursor.getDouble(0)!! }) },
            0,
        ).await()
    }
}
