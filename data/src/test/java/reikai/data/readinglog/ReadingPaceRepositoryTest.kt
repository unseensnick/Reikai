package reikai.data.readinglog

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.readinglog.PaceSample
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** Which chapters a pace is learned from, over both content types, each with its own length column. */
class ReadingPaceRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: ReadingPaceRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            database = DatabaseBindings.providesDatabase(driver)
            repository = ReadingPaceRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a sample is a read chapter with a length and time, most recently read first`(type: ContentType) = runTest {
        entry(type, id = 1)
        chapter(type, id = 1, read = true, length = 20, readAt = 1_000, duration = 60_000)
        chapter(type, id = 2, read = true, length = 30, readAt = 2_000, duration = 90_000)
        chapter(type, id = 3, read = false, length = 20, readAt = 3_000, duration = 60_000)
        chapter(type, id = 4, read = true, length = 0, readAt = 4_000, duration = 60_000)
        chapter(type, id = 5, read = true, length = 20, readAt = 5_000, duration = 0)

        repository.samples(type, limit = 10) shouldBe listOf(PaceSample(1, 90_000, 30), PaceSample(1, 60_000, 20))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `samples stop at the limit`(type: ContentType) = runTest {
        entry(type, id = 1)
        (1L..3L).forEach { chapter(type, id = it, read = true, length = 20, readAt = it * 1_000, duration = 60_000) }

        repository.samples(type, limit = 2).size shouldBe 2
    }

    @Test
    fun `a novel chapter's listened time adds up and survives its word count being written again`() = runTest {
        entry(ContentType.NOVELS, id = 1)
        chapter(ContentType.NOVELS, id = 1, read = true, length = 2_000, readAt = 1_000, duration = 600_000)
        repository.addListenedTime(chapterId = 1, ms = 200_000)
        repository.addListenedTime(chapterId = 1, ms = 200_000)

        repository.setLength(ContentType.NOVELS, chapterId = 1, units = 2_100)

        repository.samples(ContentType.NOVELS, limit = 10) shouldBe
            listOf(PaceSample(1, 600_000, 2_100, listenedMs = 400_000))
    }

    @Test
    fun `listening to a chapter before it is counted keeps the time`() = runTest {
        entry(ContentType.NOVELS, id = 1)
        chapter(ContentType.NOVELS, id = 1, read = true, length = 0, readAt = 1_000, duration = 600_000)
        repository.addListenedTime(chapterId = 1, ms = 300_000)

        repository.setLength(ContentType.NOVELS, chapterId = 1, units = 2_000)

        repository.samples(ContentType.NOVELS, limit = 10).single().listenedMs shouldBe 300_000
    }

    private suspend fun entry(type: ContentType, id: Long) = exec(
        if (type == ContentType.MANGA) {
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                "state_initialized, user_reader_flags, user_chapter_flags, " +
                "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, 1, " +
                "'m', 't', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')"
        } else {
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                "favorite_at) VALUES ($id, 'src', 'n', 't', 0, 0, 0, 0)"
        },
    )

    /** A chapter of entry 1 with its history; [length] is pages for manga and words for a novel, 0 for none. */
    private suspend fun chapter(
        type: ContentType,
        id: Long,
        read: Boolean,
        length: Long,
        readAt: Long,
        duration: Long,
    ) {
        val isRead = if (read) 1 else 0
        if (type == ContentType.MANGA) {
            exec(
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_chapter_number, remote_order, " +
                    "remote_memo, user_read, user_bookmark, user_last_page_read, state_date_fetch, page_count) " +
                    "VALUES ($id, 1, 'u$id', 'c', 1, 0, X'7B7D', $isRead, 0, 0, 0, $length)",
            )
            exec(
                "INSERT INTO history(manga_id, chapter_id, read_at, read_duration) " +
                    "VALUES (1, $id, $readAt, $duration)",
            )
        } else {
            exec(
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, source_order, " +
                    "date_fetch) VALUES ($id, 1, 'u$id', 'c', $isRead, 0, 1, 0, 0)",
            )
            exec("INSERT INTO novel_history(chapter_id, last_read, time_read) VALUES ($id, $readAt, $duration)")
            if (length > 0) repository.setLength(ContentType.NOVELS, id, length)
        }
    }

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }
}
