package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelRepositoryImpl
import tachiyomi.data.Chapters
import tachiyomi.data.Custom_manga_info
import tachiyomi.data.Custom_novel_info
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Novels
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaRepositoryImpl

/**
 * The add-to-library duplicate check also matches entries tracked to the same remote entry, for both
 * types. A tracker that never sets a remote id leaves it 0, which identifies nothing (mihonapp/mihon#4008).
 */
class DuplicateTrackMatchConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            novelsAdapter = Novels.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
            custom_manga_infoAdapter = Custom_manga_info.Adapter(genreAdapter = StringListColumnAdapter),
            custom_novel_infoAdapter = Custom_novel_info.Adapter(genreAdapter = StringListColumnAdapter),
        )
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
    fun `two entries whose tracker left the remote id unset are not duplicates`(type: Type) = runTest {
        type.seed(driver, remoteId = 0L)

        type.duplicatesOfFirst(database) shouldBe emptyList()
    }

    /** Two favourited entries with unrelated titles, each tracked on tracker 1 at [remoteId]. */
    enum class Type {
        MANGA {
            override fun statements(remoteId: Long) = listOf(
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) VALUES " +
                    "(1, 1, 'a', 'Alpha', 0, 1, 0, 0, 0, 0, 0), (2, 1, 'b', 'Beta', 0, 1, 0, 0, 0, 0, 0)",
                "INSERT INTO manga_sync(manga_id, sync_id, remote_id, title, last_chapter_read, total_chapters, " +
                    "status, score, remote_url, start_date, finish_date) VALUES " +
                    "(1, 1, $remoteId, 't', 0, 0, 0, 0, '', 0, 0), (2, 1, $remoteId, 't', 0, 0, 0, 0, '', 0, 0)",
            )

            override suspend fun duplicatesOfFirst(database: Database) =
                MangaRepositoryImpl(database).getDuplicateLibraryManga(1L, "alpha").map { it.manga.id }
        },
        NOVEL {
            override fun statements(remoteId: Long) = listOf(
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags, " +
                    "date_added) VALUES (1, 'src', 'a', 'Alpha', 0, 1, 0, 0, 0), (2, 'src', 'b', 'Beta', 0, 1, 0, 0, 0)",
                "INSERT INTO novel_tracks(novel_id, sync_id, remote_id, title, last_chapter_read, total_chapters, " +
                    "status, score, remote_url, start_date, finish_date) VALUES " +
                    "(1, 1, $remoteId, 't', 0, 0, 0, 0, '', 0, 0), (2, 1, $remoteId, 't', 0, 0, 0, 0, '', 0, 0)",
            )

            override suspend fun duplicatesOfFirst(database: Database) =
                NovelRepositoryImpl(database).getDuplicateLibraryNovel(1L, "alpha").map { it.novel.id }
        },
        ;

        abstract fun statements(remoteId: Long): List<String>

        abstract suspend fun duplicatesOfFirst(database: Database): List<Long>

        suspend fun seed(driver: JdbcSqliteDriver, remoteId: Long) =
            statements(remoteId).forEach { driver.execute(null, it, 0).await() }
    }
}
