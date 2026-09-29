package reikai.data.updateerror

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.library.updateerror.LibraryUpdateErrorRepositoryImpl
import reikai.data.novel.updateerror.NovelUpdateErrorRepositoryImpl
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The Update errors screen's store, run over the manga and novel tables. The two differ only in their
 * table and id names, so every rule the screen relies on is held here once for both.
 */
class UpdateErrorConformanceTest {

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
    fun `a second failure keeps one row with the latest message`(type: Type) = runTest {
        type.seed(driver, id = 1, title = "Berserk", inLibrary = true)
        val store = type.store(database)
        store.upsert(1, "HTTP 503")

        store.upsert(1, "Timeout")

        store.rows().map { it.title to it.message } shouldBe listOf("Berserk" to "Timeout")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `pruning drops only the errors of entries outside the library`(type: Type) = runTest {
        type.seed(driver, id = 1, title = "Kept", inLibrary = true)
        type.seed(driver, id = 2, title = "Removed", inLibrary = false)
        val store = type.store(database)
        store.upsert(1, "HTTP 503")
        store.upsert(2, "HTTP 503")

        store.deleteNonFavorites()
        type.addToLibrary(driver, id = 2)

        store.rows().map { it.title } shouldBe listOf("Kept")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `deleting by error id removes only the named errors`(type: Type) = runTest {
        type.seed(driver, id = 1, title = "Retried", inLibrary = true)
        type.seed(driver, id = 2, title = "Still failing", inLibrary = true)
        val store = type.store(database)
        store.upsert(1, "HTTP 503")
        store.upsert(2, "HTTP 503")

        store.deleteByErrorIds(store.rows().filter { it.title == "Retried" }.map { it.errorId })

        store.rows().map { it.title } shouldBe listOf("Still failing")
    }

    data class Row(val errorId: Long, val title: String, val message: String)

    interface Store {
        suspend fun upsert(entryId: Long, message: String)
        suspend fun deleteNonFavorites()
        suspend fun deleteByErrorIds(errorIds: List<Long>)
        suspend fun rows(): List<Row>
    }

    enum class Type {
        MANGA {
            override suspend fun seed(driver: JdbcSqliteDriver, id: Long, title: String, inLibrary: Boolean) {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                        "state_initialized, user_reader_flags, user_chapter_flags, " +
                        "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                        "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, 1, 'u$id', " +
                        "'$title', 0, 0, 0, 0, 0, ${if (inLibrary) "500" else "NULL"}, 0, 0, '', '{}')",
                    0,
                ).await()
            }

            override suspend fun addToLibrary(driver: JdbcSqliteDriver, id: Long) {
                driver.execute(null, "UPDATE manga SET user_favorite_at = 500 WHERE id = $id", 0).await()
            }

            override fun store(database: Database) = object : Store {
                val repository = LibraryUpdateErrorRepositoryImpl(database)
                override suspend fun upsert(entryId: Long, message: String) = repository.upsert(entryId, message)
                override suspend fun deleteNonFavorites() = repository.deleteNonFavorites()
                override suspend fun deleteByErrorIds(errorIds: List<Long>) = repository.deleteByErrorIds(errorIds)
                override suspend fun rows() =
                    repository.subscribeAll().first().map { Row(it.errorId, it.mangaTitle, it.message) }
            }
        },
        NOVEL {
            override suspend fun seed(driver: JdbcSqliteDriver, id: Long, title: String, inLibrary: Boolean) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($id, 'src', 'u$id', '$title', 0, 0, 0, ${if (inLibrary) "500" else "NULL"})",
                    0,
                ).await()
            }

            override suspend fun addToLibrary(driver: JdbcSqliteDriver, id: Long) {
                driver.execute(null, "UPDATE novels SET favorite_at = 500 WHERE _id = $id", 0).await()
            }

            override fun store(database: Database) = object : Store {
                val repository = NovelUpdateErrorRepositoryImpl(database)
                override suspend fun upsert(entryId: Long, message: String) = repository.upsert(entryId, message)
                override suspend fun deleteNonFavorites() = repository.deleteNonFavorites()
                override suspend fun deleteByErrorIds(errorIds: List<Long>) = repository.deleteByErrorIds(errorIds)
                override suspend fun rows() =
                    repository.subscribeAll().first().map { Row(it.errorId, it.novelTitle, it.message) }
            }
        }, ;

        abstract suspend fun seed(driver: JdbcSqliteDriver, id: Long, title: String, inLibrary: Boolean)

        abstract suspend fun addToLibrary(driver: JdbcSqliteDriver, id: Long)

        abstract fun store(database: Database): Store
    }
}
