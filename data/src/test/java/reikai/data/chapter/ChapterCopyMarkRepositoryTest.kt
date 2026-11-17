package reikai.data.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.ChapterCopyMark
import reikai.domain.chapter.CopyMark
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** A sync reads one series' marks per series, so the read must hold only that series' marks. */
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
}
