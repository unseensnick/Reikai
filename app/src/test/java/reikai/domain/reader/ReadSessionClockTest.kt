package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The reading session both readers time into history (mihon 553762fae). */
class ReadSessionClockTest {

    @Test
    fun `a session reads its length once`() {
        val clock = ReadSessionClock().apply { start(now = 1_000L) }

        clock.take(now = 4_000L) shouldBe 3_000L
    }

    @Test
    fun `a second save of the same session counts nothing`() {
        val clock = ReadSessionClock().apply { start(now = 1_000L) }
        clock.take(now = 4_000L)

        clock.take(now = 5_000L) shouldBe 0L
    }

    @Test
    fun `a clock that was never started counts nothing`() {
        ReadSessionClock().take(now = 5_000L) shouldBe 0L
    }
}
