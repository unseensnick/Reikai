package reikai.domain.track

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test

/** What binding a tracker to an entry pushes from local reading, for manga and novels alike. */
class BindBackfillTest {

    private val zone = TimeZone.of("Asia/Tokyo")
    private val firstReadAt = LocalDateTime(2024, 3, 1, 23, 30).toInstant(zone).toEpochMilliseconds()

    private suspend fun backfill(
        chapters: List<BindChapter>,
        lastChapterRead: Double = 0.0,
        startDate: Long = 0L,
        earliestReadAt: Long? = firstReadAt,
    ) = bindBackfill(chapters, lastChapterRead, startDate, zone) { earliestReadAt }

    @Test
    fun `a first read at 23 30 local sends that wall-clock time as UTC`() = runTest {
        val expected = LocalDateTime(2024, 3, 1, 23, 30).toInstant(TimeZone.UTC).toEpochMilliseconds()

        backfill(listOf(BindChapter(1.0, read = true))).startDate shouldBe expected
    }

    @Test
    fun `a start date the tracker already has is never overwritten`() = runTest {
        backfill(listOf(BindChapter(1.0, read = true)), startDate = 1_000L).startDate shouldBe null
    }

    @Test
    fun `an entry with nothing read sends no start date even when it has been opened`() = runTest {
        backfill(listOf(BindChapter(1.0, read = false))).startDate shouldBe null
    }

    @Test
    fun `an entry never opened sends no start date`() = runTest {
        backfill(listOf(BindChapter(1.0, read = true)), earliestReadAt = null).startDate shouldBe null
    }

    @Test
    fun `the last read pushed is the end of the unbroken read run from the start`() = runTest {
        val chapters = listOf(
            BindChapter(3.0, read = true),
            BindChapter(1.0, read = true),
            BindChapter(2.0, read = true),
            BindChapter(4.0, read = false),
            BindChapter(5.0, read = true),
        )

        backfill(chapters).lastChapterRead shouldBe 3.0
    }

    @Test
    fun `a tracker already further along keeps its last read`() = runTest {
        backfill(listOf(BindChapter(2.0, read = true)), lastChapterRead = 5.0).lastChapterRead shouldBe null
    }

    @Test
    fun `an entry with nothing read pushes no last read`() = runTest {
        backfill(listOf(BindChapter(1.0, read = false)), lastChapterRead = -2.0).lastChapterRead shouldBe null
    }
}
