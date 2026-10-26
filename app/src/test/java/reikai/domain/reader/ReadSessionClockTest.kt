package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** The reading session both readers time into history (mihon 553762fae). */
class ReadSessionClockTest {

    @Test
    fun `a session reads its length once`() {
        val clock = ReadSessionClock().apply { start(now = at(1_000L)) }

        clock.take(now = at(4_000L)) shouldBe 3_000L
    }

    @Test
    fun `a second save of the same session counts nothing`() {
        val clock = ReadSessionClock().apply { start(now = at(1_000L)) }
        clock.take(now = at(4_000L))

        clock.take(now = at(5_000L)) shouldBe 0L
    }

    /** Read aloud kept the session running through the pause, and that time is the reader's. */
    @Test
    fun `resuming keeps a session that is still running`() {
        val clock = ReadSessionClock().apply { start(now = at(1_000L)) }

        clock.resume(now = at(4_000L))

        clock.take(now = at(5_000L)) shouldBe 4_000L
    }

    @Test
    fun `resuming a stopped clock starts it`() {
        val clock = ReadSessionClock().apply { resume(now = at(4_000L)) }

        clock.take(now = at(5_000L)) shouldBe 1_000L
    }

    @Test
    fun `a clock that was never started counts nothing`() {
        ReadSessionClock().take(now = at(5_000L)) shouldBe 0L
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)
}
