package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelRepositoryImpl
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl

/**
 * The add-to-library duplicate check also matches entries tracked to the same remote entry, for both
 * types. A tracker that never sets a remote id leaves it 0, which identifies nothing (mihonapp/mihon#4008),
 * and a blank title names nothing either (mihonapp/mihon#3308).
 */
class DuplicateTrackMatchConformanceTest {

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
    fun `an entry tracked to the same remote entry is a duplicate whatever its title`(type: Type) = runTest {
        type.seed(driver, remoteId = 42L)

        type.duplicatesOfFirst(database) shouldBe listOf(2L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `one remote id on two different trackers is no duplicate`(type: Type) = runTest {
        type.seed(driver, remoteId = 42L, secondTracker = 2L)

        type.duplicatesOfFirst(database) shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `two entries whose tracker left the remote id unset are not duplicates`(type: Type) = runTest {
        type.seed(driver, remoteId = 0L)

        type.duplicatesOfFirst(database) shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a blank title matches no entry by title`(type: Type) = runTest {
        type.seed(driver, remoteId = 0L)

        type.duplicatesOfFirst(database, title = "") shouldBe emptyList()
    }

    /** Two favourited entries with unrelated titles, tracked at [remoteId] on tracker 1 and [secondTracker]. */
    enum class Type {
        MANGA {
            override fun statements(remoteId: Long, secondTracker: Long) = listOf(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES (1, 1, 'a', " +
                    "'Alpha', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}'), (2, 1, 'b', 'Beta', 0, 0, 0, 0, 0, " +
                    "0, 0, 0, '', '{}')",
                "INSERT INTO manga_track(manga_id, tracker_id, remote_id, title, last_chapter_read, " +
                    "total_chapters, status, score, remote_url, start_date, finish_date) VALUES (1, 1, $remoteId, " +
                    "'t', 0, 0, 0, 0, '', 0, 0), (2, $secondTracker, $remoteId, 't', 0, 0, 0, 0, '', 0, 0)",
            )

            override suspend fun duplicatesOfFirst(database: Database, title: String) =
                MangaRepositoryImpl(database).getDuplicateLibraryManga(1L, title).map { it.manga.id }
        },
        NOVEL {
            override fun statements(remoteId: Long, secondTracker: Long) = listOf(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES (1, 'src', 'a', 'Alpha', 0, 0, 0, 0), (2, 'src', 'b', 'Beta', 0, 0, 0, 0)",
                "INSERT INTO novel_tracks(novel_id, sync_id, remote_id, title, last_chapter_read, total_chapters, " +
                    "status, score, remote_url, start_date, finish_date) VALUES " +
                    "(1, 1, $remoteId, 't', 0, 0, 0, 0, '', 0, 0), (2, $secondTracker, $remoteId, 't', 0, 0, 0, 0, '', 0, 0)",
            )

            override suspend fun duplicatesOfFirst(database: Database, title: String) =
                NovelRepositoryImpl(database).getDuplicateLibraryNovel(1L, title).map { it.novel.id }
        },
        ;

        abstract fun statements(remoteId: Long, secondTracker: Long): List<String>

        abstract suspend fun duplicatesOfFirst(database: Database, title: String = "alpha"): List<Long>

        suspend fun seed(driver: JdbcSqliteDriver, remoteId: Long, secondTracker: Long = 1L) =
            statements(remoteId, secondTracker).forEach { driver.execute(null, it, 0).await() }
    }
}
