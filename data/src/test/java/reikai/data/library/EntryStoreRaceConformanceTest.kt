package reikai.data.library

import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.model.Novel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * Storing an entry a source lists resolves to the one row that source and url may have, even when
 * another writer stores it between the lookup and the insert. A trigger plays that writer: it stores
 * the same entry, under its own id, just before the insert under test lands.
 */
class EntryStoreRaceConformanceTest {

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
    fun `storing an entry another writer stored first resolves to that writer's row`(type: Type) = runTest {
        driver.execute(null, type.racer, 0).await()

        type.store(database) shouldBe RACER_ID
    }

    // The device driver runs a query only when it is awaited, while SQLDelight announces a RETURNING
    // write as soon as the query is built, so outside a transaction a watcher re-reading on that notice
    // finds no row, and is never told again.
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a watcher re-reading on the store's notice finds the stored row`(type: Type) = runTest {
        val reads = mutableListOf<Long?>()
        driver.addListener(type.table, listener = Query.Listener { reads += type.storedId(driver) })

        type.store(DatabaseBindings.providesDatabase(AwaitedQueryDriver(driver)))

        reads.first() shouldNotBe null
    }

    enum class Type(val racer: String, val table: String, private val idSql: String) {
        MANGA(
            "CREATE TRIGGER racer BEFORE INSERT ON manga WHEN NEW.id IS NOT $RACER_ID BEGIN " +
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, remote_update_strategy, " +
                "remote_memo, user_notes, user_reader_flags, user_chapter_flags, state_chapter_fetch_interval, " +
                "state_cover_last_modified, state_initialized) " +
                "VALUES ($RACER_ID, NEW.source_id, NEW.remote_url, 'racer', 0, 0, '{}', '', 0, 0, 0, 0, 0); END",
            "manga",
            "SELECT id FROM manga WHERE remote_url = '/m'",
        ) {
            override suspend fun store(database: Database) = MangaRepositoryImpl(database)
                .insertNetworkManga(listOf(Manga.create().copy(source = 1L, url = "/m", title = "M")))
                .single()
                .id
        },
        NOVEL(
            "CREATE TRIGGER racer BEFORE INSERT ON novels WHEN NEW._id IS NOT $RACER_ID BEGIN " +
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags) " +
                "VALUES ($RACER_ID, NEW.source, NEW.url, 'racer', 0, 0, 0); END",
            "novels",
            "SELECT _id FROM novels WHERE url = '/n'",
        ) {
            override suspend fun store(database: Database) = NovelRepositoryImpl(database)
                .insertOrGet(Novel.create().copy(source = "s", url = "/n", title = "N"))
                ?.id
        },
        ;

        abstract suspend fun store(database: Database): Long?

        fun storedId(driver: SqlDriver): Long? = driver.executeQuery(
            identifier = null,
            sql = idSql,
            mapper = { cursor: SqlCursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            parameters = 0,
        ).value
    }

    /** Defers every query until it is awaited, as the device driver does. */
    private class AwaitedQueryDriver(private val inner: SqlDriver) : SqlDriver by inner {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = QueryResult.AsyncValue {
            inner.executeQuery(identifier, sql, mapper, parameters, binders).await()
        }
    }

    private companion object {
        const val RACER_ID = 42L
    }
}
