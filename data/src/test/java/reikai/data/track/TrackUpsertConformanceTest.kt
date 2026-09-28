package reikai.data.track

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelTrackRepositoryImpl
import reikai.domain.novel.model.NovelTrack
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.track.model.Track

/**
 * Writing a track for an entry and tracker that already have one updates that row in place, for both
 * types, so anything keyed to its id (a queued tracking update) still finds it (mihon 9a77baedc).
 */
class TrackUpsertConformanceTest {

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
    fun `a second write for the same tracker keeps the row's id`(type: Type) = runTest {
        driver.execute(null, type.entryRow, 0).await()
        val first = type.upsertAndReadIds(database, lastChapterRead = 1.0)

        type.upsertAndReadIds(database, lastChapterRead = 2.0) shouldBe first
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a second write for the same tracker updates the row`(type: Type) = runTest {
        driver.execute(null, type.entryRow, 0).await()
        type.upsertAndReadIds(database, lastChapterRead = 1.0)
        type.upsertAndReadIds(database, lastChapterRead = 2.0)

        type.lastChaptersRead(database) shouldBe listOf(2.0)
    }

    enum class Type(val entryRow: String) {
        MANGA(
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                "state_initialized, user_reader_flags, user_chapter_flags, " +
                "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES (1, 1, 'u', 'T', " +
                "0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
        ) {
            override suspend fun upsertAndReadIds(database: Database, lastChapterRead: Double): List<Long> {
                val repository = TrackRepositoryImpl(database)
                repository.upsert(
                    Track(
                        id = 0, mangaId = 1, trackerId = 2, remoteId = 3, libraryId = null, title = "T",
                        lastChapterRead = lastChapterRead, totalChapters = 0, status = 1, score = 0.0,
                        remoteUrl = "", startDate = 0, finishDate = 0, private = false,
                    ),
                )
                return repository.getTracksByMangaId(1).map { it.id }
            }

            override suspend fun lastChaptersRead(database: Database) =
                TrackRepositoryImpl(database).getTracksByMangaId(1).map { it.lastChapterRead }
        },
        NOVEL(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                "favorite_at) VALUES (1, 'src', 'u', 'T', 0, 0, 0, 0)",
        ) {
            override suspend fun upsertAndReadIds(database: Database, lastChapterRead: Double): List<Long> {
                val repository = NovelTrackRepositoryImpl(database)
                repository.upsert(
                    NovelTrack(
                        id = 0, novelId = 1, trackerId = 2, remoteId = 3, libraryId = null, title = "T",
                        lastChapterRead = lastChapterRead, totalChapters = 0, status = 1, score = 0.0,
                        remoteUrl = "", startDate = 0, finishDate = 0, private = false,
                    ),
                )
                return repository.getTracksByNovelId(1).map { it.id }
            }

            override suspend fun lastChaptersRead(database: Database) =
                NovelTrackRepositoryImpl(database).getTracksByNovelId(1).map { it.lastChapterRead }
        },
        ;

        abstract suspend fun upsertAndReadIds(database: Database, lastChapterRead: Double): List<Long>

        abstract suspend fun lastChaptersRead(database: Database): List<Double>
    }
}
