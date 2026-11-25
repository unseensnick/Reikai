package reikai.data.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.ChapterCopyMark
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.CopyMarkReport
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** The copy marks as stored, over real SQL for both content types. */
class ChapterCopyMarkRepositoryTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `one owner's marks hold only that owner's chapters`(type: ContentType) = runTest {
        Database.Schema.create(driver).await()
        val repository = ChapterCopyMarkRepositoryImpl(DatabaseBindings.providesDatabase(driver), backgroundScope)
        repository.set(
            type,
            listOf(
                ChapterCopyMark(1L, "/one", CopyMark.NeverSkip),
                ChapterCopyMark(2L, "/two", CopyMark.Group(5L)),
            ),
        )

        repository.loadedMarks(type, 1L) shouldBe mapOf("/one" to CopyMark.NeverSkip)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `clearing a chapter's mark leaves another owner's mark on the same url`(type: ContentType) = runTest {
        Database.Schema.create(driver).await()
        val repository = ChapterCopyMarkRepositoryImpl(DatabaseBindings.providesDatabase(driver), backgroundScope)
        repository.set(
            type,
            listOf(ChapterCopyMark(1L, "/one", CopyMark.NeverSkip), ChapterCopyMark(2L, "/one", CopyMark.NeverSkip)),
        )

        repository.clear(type, 1L, listOf("/one"))

        (repository.loadedMarks(type, 1L) to repository.loadedMarks(type, 2L)) shouldBe
            (emptyMap<String, CopyMark>() to mapOf("/one" to CopyMark.NeverSkip))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a report names the series and the chapter a mark is on`(type: ContentType) = runTest {
        Database.Schema.create(driver).await()
        val repository = ChapterCopyMarkRepositoryImpl(DatabaseBindings.providesDatabase(driver), backgroundScope)
        val source = seedSeriesWithChapter(type, url = "/one")
        repository.set(type, listOf(ChapterCopyMark(1L, "/one", CopyMark.Group(5L))))

        repository.report(type) shouldBe
            listOf(CopyMarkReport("Title", source, "/one", "Chapter 1", 1.0, null, CopyMark.Group(5L)))
    }

    /** Series 1, titled "Title", with one chapter "Chapter 1" at [url]; returns its source as a report names it. */
    private suspend fun seedSeriesWithChapter(type: ContentType, url: String): String = when (type) {
        ContentType.NOVELS -> {
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES (1, 'src', '/novel', 'Title', 0, 0, 0, 0)",
                0,
            ).await()
            driver.execute(
                null,
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, source_order, " +
                    "date_fetch, date_upload) VALUES (10, 1, '$url', 'Chapter 1', 0, 0, 1.0, 0, 0, 0)",
                0,
            ).await()
            "src"
        }
        else -> {
            driver.execute(
                null,
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) " +
                    "VALUES (1, 7, '/manga', 'Title', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                0,
            ).await()
            driver.execute(
                null,
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES (10, 1, '$url', 'Chapter 1', NULL, 0, 0, 0, " +
                    "1.0, 0, 0, 0, '{}')",
                0,
            ).await()
            "7"
        }
    }
}
