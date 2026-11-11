package reikai.data.chapter

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.ChapterTombstone
import reikai.domain.chapter.Revival
import reikai.domain.chapter.TombstoneSession
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import kotlin.time.Instant

/**
 * One rule for every write of a kept reading state onto something that already holds state, for both
 * types: read and bookmark stick, progress, length, listened time and history time take the larger, the
 * fetch date the earlier, history the later read, sessions the union. The paths that meet an existing
 * state: a capture onto an older tombstone, a tombstone applied onto a row, a restored tombstone onto a
 * tombstone or onto a row, and a restored chapter onto the tombstone waiting for it.
 */
class ChapterTombstoneFoldConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private val repository get() = ChapterTombstoneRepositoryImpl(database)

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() = driver.close()

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a row captured onto an older kept state keeps the newer of each`(type: Type) = runTest {
        type.seedOwner(driver)
        type.seedTombstone(driver, URL, OLDER)
        type.seedRow(driver, CHAPTER, URL, NEWER)

        repository.capturing(type.contentType, OWNER, listOf(CHAPTER), emptyList()) {
            type.deleteRow(database, CHAPTER)
        }

        type.tombstoneState(driver, URL) shouldBe type.of(FOLDED)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a kept state applied onto a row keeps the newer of each and is spent`(type: Type) = runTest {
        type.seedOwner(driver)
        type.seedTombstone(driver, URL, OLDER)
        type.seedRow(driver, CHAPTER, URL, NEWER)

        repository.capturing(type.contentType, OWNER, emptyList(), listOf(Revival(URL, URL))) {}

        (type.rowState(driver, URL) to type.count(driver, type.tombstoneTable)) shouldBe (type.of(FOLDED) to 0L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restored kept state folds onto the one already there`(type: Type) = runTest {
        type.seedOwner(driver)
        type.seedTombstone(driver, URL, OLDER)

        repository.restore(type.contentType, OWNER, listOf(NEWER.tombstone()))

        type.tombstoneState(driver, URL) shouldBe type.of(FOLDED)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restored kept state meets the row at its url and is spent on it`(type: Type) = runTest {
        type.seedOwner(driver)
        type.seedRow(driver, CHAPTER, URL, OLDER)

        repository.restore(type.contentType, OWNER, listOf(NEWER.tombstone()))

        (type.rowState(driver, URL) to type.count(driver, type.tombstoneTable)) shouldBe (type.of(FOLDED) to 0L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restored chapter spends the kept state waiting at its url`(type: Type) = runTest {
        type.seedOwner(driver)
        type.seedTombstone(driver, URL, NEWER)
        type.seedRow(driver, CHAPTER, URL, OLDER)

        repository.restore(type.contentType, OWNER, emptyList())

        (type.rowState(driver, URL) to type.count(driver, type.tombstoneTable)) shouldBe (type.of(FOLDED) to 0L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a backup reads a kept state back whole`(type: Type) = runTest {
        type.seedOwner(driver)
        type.seedTombstone(driver, URL, NEWER)

        repository.getByOwners(type.contentType, listOf(OWNER)).getValue(OWNER) shouldBe listOf(NEWER.tombstone(type))
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `forgetting a prefix drops the kept states under it with their sessions and no other`(type: Type) = runTest {
        type.seedOwner(driver)
        type.seedTombstone(driver, "Series/v1/c1", NEWER)
        type.seedTombstone(driver, "Series/v10/c1", NEWER)

        repository.forget(type.contentType, OWNER, listOf("Series/v1/"))

        listOf(type.urls(driver), type.count(driver, type.sessionTable)) shouldBe listOf(listOf("Series/v10/c1"), 1L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a series removed from the database drops its kept states and their sessions`(type: Type) = runTest {
        type.seedOwner(driver)
        type.seedTombstone(driver, URL, NEWER)

        type.deleteOwner(driver)

        (type.count(driver, type.tombstoneTable) to type.count(driver, type.sessionTable)) shouldBe (0L to 0L)
    }

    /** The user state hanging off one chapter, row or tombstone, in the shape both types share. */
    data class State(
        val read: Boolean,
        val bookmark: Boolean,
        val progress: Long,
        val length: Long,
        val listened: Long,
        val dateFetch: Long,
        val historyReadAt: Long?,
        val historyDuration: Long,
        val sessions: List<Pair<Long, Long>>,
    ) {
        fun tombstone(type: Type? = null) = ChapterTombstone(
            url = URL,
            number = 1.0,
            read = read,
            bookmark = bookmark,
            progress = progress,
            length = length,
            listenedMs = if (type == Type.MANGA) null else listened,
            dateFetch = Instant.fromEpochMilliseconds(dateFetch),
            historyReadAt = historyReadAt?.let(Instant::fromEpochMilliseconds),
            historyDuration = historyDuration,
            sessions = sessions.map { TombstoneSession(it.first, it.second) },
            removedAt = 0,
        )
    }

    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA) {
            override val tombstoneTable = "manga_chapter_tombstone"
            override val sessionTable = "manga_chapter_tombstone_session"

            override suspend fun seedOwner(driver: JdbcSqliteDriver) = driver.exec(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($OWNER, 0, 'Series', 'T', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
            )

            override suspend fun seedRow(driver: JdbcSqliteDriver, id: Long, url: String, state: State) = with(state) {
                driver.exec(
                    "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_chapter_number, remote_order, " +
                        "remote_memo, user_read, user_bookmark, user_last_page_read, page_count, state_date_fetch) " +
                        "VALUES ($id, $OWNER, '$url', 'c', 1.0, 0, '{}', ${read.bit()}, ${bookmark.bit()}, " +
                        "$progress, $length, $dateFetch)",
                )
                driver.exec(
                    "INSERT INTO history(manga_id, chapter_id, read_at, read_duration) " +
                        "VALUES ($OWNER, $id, $historyReadAt, $historyDuration)",
                )
                sessions.forEach { (at, duration) ->
                    driver.exec(
                        "INSERT INTO reading_session(chapter_id, ended_at, duration) VALUES ($id, $at, $duration)",
                    )
                }
            }

            override suspend fun seedTombstone(driver: JdbcSqliteDriver, url: String, state: State) = with(state) {
                driver.exec(
                    "INSERT INTO manga_chapter_tombstone(manga_id, url, chapter_number, read, bookmark, " +
                        "last_page_read, page_count, date_fetch, history_read_at, history_read_duration, removed_at) " +
                        "VALUES " +
                        "($OWNER, '$url', 1.0, ${read.bit()}, ${bookmark.bit()}, $progress, $length, $dateFetch, " +
                        "$historyReadAt, $historyDuration, 0)",
                )
                sessions.forEach { (at, duration) ->
                    driver.exec(
                        "INSERT INTO manga_chapter_tombstone_session(manga_id, url, ended_at, duration) " +
                            "VALUES ($OWNER, '$url', $at, $duration)",
                    )
                }
            }

            override suspend fun rowState(driver: JdbcSqliteDriver, url: String): State {
                val row = driver.rows(
                    "SELECT C.user_read, C.user_bookmark, C.user_last_page_read, C.page_count, C.state_date_fetch, " +
                        "H.read_at, H.read_duration FROM chapter C LEFT JOIN history H ON H.chapter_id = C.id " +
                        "WHERE C.remote_url = '$url'",
                ) { c -> (0..6).map { c.getLong(it) } }.single()
                val sessions = driver.rows(
                    "SELECT S.ended_at, S.duration FROM reading_session S " +
                        "INNER JOIN chapter C ON C.id = S.chapter_id WHERE C.remote_url = '$url' ORDER BY S.ended_at",
                ) { it.getLong(0)!! to it.getLong(1)!! }
                return State(row[0] == 1L, row[1] == 1L, row[2]!!, row[3]!!, 0, row[4]!!, row[5], row[6] ?: 0, sessions)
            }

            override suspend fun tombstoneState(driver: JdbcSqliteDriver, url: String): State {
                val row = driver.rows(
                    "SELECT read, bookmark, last_page_read, page_count, date_fetch, history_read_at, " +
                        "history_read_duration FROM manga_chapter_tombstone WHERE url = '$url'",
                ) { c -> (0..6).map { c.getLong(it) } }.single()
                val sessions = driver.rows(
                    "SELECT ended_at, duration FROM manga_chapter_tombstone_session WHERE url = '$url' " +
                        "ORDER BY ended_at",
                ) { it.getLong(0)!! to it.getLong(1)!! }
                return State(row[0] == 1L, row[1] == 1L, row[2]!!, row[3]!!, 0, row[4]!!, row[5], row[6] ?: 0, sessions)
            }

            override suspend fun deleteRow(database: Database, id: Long) {
                database.chapterQueries.removeChaptersWithIds(listOf(id))
            }

            override suspend fun deleteOwner(driver: JdbcSqliteDriver) =
                driver.exec("DELETE FROM manga WHERE id = $OWNER")
        },
        NOVEL(ContentType.NOVELS) {
            override val tombstoneTable = "novel_chapter_tombstone"
            override val sessionTable = "novel_chapter_tombstone_session"

            override suspend fun seedOwner(driver: JdbcSqliteDriver) = driver.exec(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($OWNER, 'src', 'Series', 'T', 0, 0, 0, 0)",
            )

            override suspend fun seedRow(driver: JdbcSqliteDriver, id: Long, url: String, state: State) = with(state) {
                driver.exec(
                    "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                        "chapter_number, source_order, date_fetch) VALUES ($id, $OWNER, '$url', 'c', ${read.bit()}, " +
                        "${bookmark.bit()}, $progress, 1.0, 0, $dateFetch)",
                )
                driver.exec(
                    "INSERT INTO novel_history(chapter_id, last_read, time_read) " +
                        "VALUES ($id, $historyReadAt, $historyDuration)",
                )
                driver.exec(
                    "INSERT INTO novel_chapter_words(chapter_id, words, listened_ms) VALUES ($id, $length, $listened)",
                )
                sessions.forEach { (at, duration) ->
                    driver.exec(
                        "INSERT INTO novel_reading_session(chapter_id, ended_at, duration) " +
                            "VALUES ($id, $at, $duration)",
                    )
                }
            }

            override suspend fun seedTombstone(driver: JdbcSqliteDriver, url: String, state: State) = with(state) {
                driver.exec(
                    "INSERT INTO novel_chapter_tombstone(novel_id, url, chapter_number, read, bookmark, " +
                        "last_text_progress, words, listened_ms, date_fetch, history_last_read, history_time_read, " +
                        "removed_at) VALUES ($OWNER, '$url', 1.0, ${read.bit()}, ${bookmark.bit()}, $progress, " +
                        "$length, $listened, $dateFetch, $historyReadAt, $historyDuration, 0)",
                )
                sessions.forEach { (at, duration) ->
                    driver.exec(
                        "INSERT INTO novel_chapter_tombstone_session(novel_id, url, ended_at, duration) " +
                            "VALUES ($OWNER, '$url', $at, $duration)",
                    )
                }
            }

            override suspend fun rowState(driver: JdbcSqliteDriver, url: String): State {
                val row = driver.rows(
                    "SELECT C.read, C.bookmark, C.last_text_progress, W.words, W.listened_ms, C.date_fetch, " +
                        "H.last_read, H.time_read FROM novel_chapters C " +
                        "LEFT JOIN novel_history H ON H.chapter_id = C._id " +
                        "LEFT JOIN novel_chapter_words W ON W.chapter_id = C._id WHERE C.url = '$url'",
                ) { c -> (0..7).map { c.getLong(it) } }.single()
                val sessions = driver.rows(
                    "SELECT S.ended_at, S.duration FROM novel_reading_session S " +
                        "INNER JOIN novel_chapters C ON C._id = S.chapter_id WHERE C.url = '$url' ORDER BY S.ended_at",
                ) { it.getLong(0)!! to it.getLong(1)!! }
                return State(
                    row[0] == 1L, row[1] == 1L, row[2]!!, row[3] ?: 0, row[4] ?: 0, row[5]!!, row[6], row[7] ?: 0,
                    sessions,
                )
            }

            override suspend fun tombstoneState(driver: JdbcSqliteDriver, url: String): State {
                val row = driver.rows(
                    "SELECT read, bookmark, last_text_progress, words, listened_ms, date_fetch, history_last_read, " +
                        "history_time_read FROM novel_chapter_tombstone WHERE url = '$url'",
                ) { c -> (0..7).map { c.getLong(it) } }.single()
                val sessions = driver.rows(
                    "SELECT ended_at, duration FROM novel_chapter_tombstone_session WHERE url = '$url' " +
                        "ORDER BY ended_at",
                ) { it.getLong(0)!! to it.getLong(1)!! }
                return State(
                    row[0] == 1L, row[1] == 1L, row[2]!!, row[3] ?: 0, row[4] ?: 0, row[5]!!, row[6], row[7] ?: 0,
                    sessions,
                )
            }

            override suspend fun deleteRow(database: Database, id: Long) {
                database.novel_chaptersQueries.delete(id)
            }

            override suspend fun deleteOwner(driver: JdbcSqliteDriver) =
                driver.exec("DELETE FROM novels WHERE _id = $OWNER")
        },
        ;

        abstract val tombstoneTable: String
        abstract val sessionTable: String

        abstract suspend fun seedOwner(driver: JdbcSqliteDriver)

        abstract suspend fun seedRow(driver: JdbcSqliteDriver, id: Long, url: String, state: State)

        abstract suspend fun seedTombstone(driver: JdbcSqliteDriver, url: String, state: State)

        abstract suspend fun rowState(driver: JdbcSqliteDriver, url: String): State

        abstract suspend fun tombstoneState(driver: JdbcSqliteDriver, url: String): State

        abstract suspend fun deleteRow(database: Database, id: Long)

        abstract suspend fun deleteOwner(driver: JdbcSqliteDriver)

        /** [state] as this type keeps it: a manga chapter has no read-aloud time. */
        fun of(state: State) = if (this == MANGA) state.copy(listened = 0) else state

        suspend fun urls(driver: JdbcSqliteDriver) =
            driver.rows("SELECT url FROM $tombstoneTable ORDER BY url") { it.getString(0)!! }

        suspend fun count(driver: JdbcSqliteDriver, table: String) =
            driver.rows("SELECT count(*) FROM $table") { it.getLong(0)!! }.single()
    }

    companion object {
        private const val OWNER = 1L
        private const val CHAPTER = 7L
        private const val URL = "Series/c1"

        /** State read first: read, further along in a shorter chapter, fetched earlier, read earlier, one session. */
        private val OLDER = State(true, false, 9, 20, 500, 300, 100, 60, listOf(1_000L to 30L))

        /** State read since: unread again, a page in, a longer chapter, fetched later, read later, another session. */
        private val NEWER = State(false, true, 3, 25, 400, 500, 200, 90, listOf(2_000L to 40L))

        private val FOLDED = State(true, true, 9, 25, 500, 300, 200, 90, listOf(1_000L to 30L, 2_000L to 40L))

        private fun Boolean.bit() = if (this) 1 else 0

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
