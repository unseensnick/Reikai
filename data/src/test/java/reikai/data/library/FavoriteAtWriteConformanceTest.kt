package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.model.NovelUpdate
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * Library membership is one nullable date on both types (mihon 986f46c09), so a partial update has to
 * tell "take it out of the library" (write null) apart from "leave membership alone" (write nothing).
 */
class FavoriteAtWriteConformanceTest {

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
    fun `taking an entry out of the library clears its date`(type: Type) = runTest {
        type.seed(driver, favoriteAt = 500L)

        type.write(database, favoriteAtSet = true, notes = null)

        type.favoriteAt(database) shouldBe null
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an update that leaves membership alone keeps the date`(type: Type) = runTest {
        type.seed(driver, favoriteAt = 500L)

        type.write(database, favoriteAtSet = false, notes = "n")

        type.favoriteAt(database) shouldBe 500L
    }

    enum class Type {
        MANGA {
            override suspend fun seed(driver: JdbcSqliteDriver, favoriteAt: Long) {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                        "state_initialized, user_reader_flags, user_chapter_flags, " +
                        "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                        "state_chapter_fetch_interval, user_notes, remote_memo) VALUES (1, 1, 'u', 'T', " +
                        "0, 0, 0, 0, 0, $favoriteAt, 0, 0, '', '{}')",
                    0,
                ).await()
            }

            override suspend fun write(database: Database, favoriteAtSet: Boolean, notes: String?) {
                MangaRepositoryImpl(database).update(
                    MangaUpdate(1) {
                        if (favoriteAtSet) favoriteAt = null
                        this.notes = notes
                    },
                )
            }

            override suspend fun favoriteAt(database: Database) = MangaRepositoryImpl(
                database,
            ).getMangaById(1).favoriteAt
        },
        NOVEL {
            override suspend fun seed(driver: JdbcSqliteDriver, favoriteAt: Long) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES (1, 'src', 'u', 'T', 0, 0, 0, $favoriteAt)",
                    0,
                ).await()
            }

            override suspend fun write(database: Database, favoriteAtSet: Boolean, notes: String?) {
                NovelRepositoryImpl(database).update(
                    NovelUpdate(1) {
                        if (favoriteAtSet) favoriteAt = null
                        this.notes = notes
                    },
                )
            }

            override suspend fun favoriteAt(database: Database) = NovelRepositoryImpl(database).getById(1)?.favoriteAt
        }, ;

        abstract suspend fun seed(driver: JdbcSqliteDriver, favoriteAt: Long)

        abstract suspend fun write(database: Database, favoriteAtSet: Boolean, notes: String?)

        abstract suspend fun favoriteAt(database: Database): Long?
    }
}
