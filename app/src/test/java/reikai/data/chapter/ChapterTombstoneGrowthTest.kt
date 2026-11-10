package reikai.data.chapter

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.Revival
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import java.io.File

/**
 * Kept reading state is bounded by the library, not by time: a library of ten local series of a thousand
 * chapters, all removed and all returning, over and over, holds ten thousand kept states while they are
 * gone, none once they are back, and the database file stops growing after the first round, since each
 * revive spends its state and the pages it held are used again.
 */
class ChapterTombstoneGrowthTest {

    @TempDir
    lateinit var tmp: File

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `removing and reviving a whole library repeatedly leaves nothing behind`(type: ContentType) = runTest {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(tmp, "$type.db").absolutePath}")
        driver.exec("PRAGMA foreign_keys = ON")
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val repository = ChapterTombstoneRepositoryImpl(database)
        val novel = type == ContentType.NOVELS
        database.transaction { (1..SERIES).forEach { owner -> seedOwner(driver, novel, owner.toLong()) } }
        val pages = mutableListOf<Long>()
        val kept = mutableListOf<Long>()

        repeat(ROUNDS) {
            (1..SERIES).forEach { owner ->
                database.transaction { (1..CHAPTERS).forEach { seedChapter(driver, novel, owner.toLong(), it) } }
                val ids = (1..CHAPTERS).map { chapterId(owner, it) }
                repository.capturing(type, owner.toLong(), ids, emptyList()) {
                    if (novel) {
                        ids.forEach {
                            database.novel_chaptersQueries.delete(it)
                        }
                    } else {
                        database.chapterQueries.removeChaptersWithIds(ids)
                    }
                }
            }
            kept += driver.count(if (novel) "novel_chapter_tombstone" else "manga_chapter_tombstone")
            (1..SERIES).forEach { owner ->
                val revivals = (1..CHAPTERS).map { url(owner, it) }.map { Revival(it, it) }
                repository.capturing(type, owner.toLong(), emptyList(), revivals) {
                    database.transaction { (1..CHAPTERS).forEach { seedChapter(driver, novel, owner.toLong(), it) } }
                }
                database.transaction { (1..CHAPTERS).forEach { deleteChapter(driver, novel, chapterId(owner, it)) } }
            }
            kept += driver.count(if (novel) "novel_chapter_tombstone" else "manga_chapter_tombstone")
            pages += driver.pragma("page_count")
        }
        println(
            "$type: kept states per round (gone, back) $kept; file pages per round $pages " +
                "of ${driver.pragma("page_size")} bytes",
        )
        driver.close()

        kept shouldBe List(ROUNDS) { listOf(SERIES * CHAPTERS.toLong(), 0L) }.flatten()
        pages.last() shouldBeLessThanOrEqual pages.first() + pages.first() / 10
    }

    private fun chapterId(owner: Int, i: Int) = owner * 10_000L + i

    private fun url(owner: Int, i: Int) = "Series$owner/c$i"

    private suspend fun seedOwner(driver: JdbcSqliteDriver, novel: Boolean, id: Long) = driver.exec(
        if (novel) {
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                "VALUES ($id, 'src', 'Series$id', 'T', 0, 0, 0, 0)"
        } else {
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                "($id, 0, 'Series$id', 'T', 0, 0, 0, 0, 0, 0, 0, '', '{}')"
        },
    )

    /** A read chapter with a history row and two sessions, the shape a kept state carries. */
    private suspend fun seedChapter(driver: JdbcSqliteDriver, novel: Boolean, owner: Long, i: Int) {
        val id = chapterId(owner.toInt(), i)
        val url = url(owner.toInt(), i)
        if (novel) {
            driver.exec(
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch) VALUES ($id, $owner, '$url', 'c', 1, 0, 50, $i, $i, 1)",
            )
            driver.exec("INSERT OR IGNORE INTO novel_history(chapter_id, last_read, time_read) VALUES ($id, 100, 60)")
            driver.exec(
                "INSERT OR IGNORE INTO novel_chapter_words(chapter_id, words, listened_ms) VALUES ($id, 900, 0)",
            )
            driver.exec(
                "INSERT OR IGNORE INTO novel_reading_session(chapter_id, ended_at, duration) VALUES ($id, 1, 30)",
            )
            driver.exec(
                "INSERT OR IGNORE INTO novel_reading_session(chapter_id, ended_at, duration) VALUES ($id, 2, 30)",
            )
        } else {
            driver.exec(
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_chapter_number, remote_order, " +
                    "remote_memo, user_read, user_bookmark, user_last_page_read, page_count, state_date_fetch) " +
                    "VALUES ($id, $owner, '$url', 'c', $i, $i, '{}', 1, 0, 5, 20, 1)",
            )
            driver.exec(
                "INSERT OR IGNORE INTO history(manga_id, chapter_id, read_at, read_duration) VALUES ($owner, $id, 100, 60)",
            )
            driver.exec("INSERT OR IGNORE INTO reading_session(chapter_id, ended_at, duration) VALUES ($id, 1, 30)")
            driver.exec("INSERT OR IGNORE INTO reading_session(chapter_id, ended_at, duration) VALUES ($id, 2, 30)")
        }
    }

    private suspend fun deleteChapter(driver: JdbcSqliteDriver, novel: Boolean, id: Long) =
        driver.exec(if (novel) "DELETE FROM novel_chapters WHERE _id = $id" else "DELETE FROM chapter WHERE id = $id")

    private suspend fun JdbcSqliteDriver.exec(sql: String) {
        execute(null, sql, 0).await()
    }

    private suspend fun JdbcSqliteDriver.count(table: String): Long = long("SELECT count(*) FROM $table")

    private suspend fun JdbcSqliteDriver.pragma(name: String): Long = long("PRAGMA $name")

    private suspend fun JdbcSqliteDriver.long(sql: String): Long = executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(cursor.next().value.let { cursor.getLong(0)!! }) },
        0,
    ).await()

    private companion object {
        const val SERIES = 10
        const val CHAPTERS = 1000
        const val ROUNDS = 3
    }
}
