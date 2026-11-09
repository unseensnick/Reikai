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

    /** The reader's menu was open from 2 s to 5 s, which is not reading. */
    @Test
    fun `time held counts nothing`() {
        val clock = ReadSessionClock().apply { start(now = at(1_000L)) }
        clock.hold(held = true, now = at(2_000L))
        clock.hold(held = false, now = at(5_000L))

        clock.take(now = at(6_000L)) shouldBe 2_000L
    }

    @Test
    fun `a session taken while held counts up to the hold`() {
        val clock = ReadSessionClock().apply { start(now = at(1_000L)) }
        clock.hold(held = true, now = at(3_000L))

        clock.take(now = at(9_000L)) shouldBe 2_000L
    }

    /** Coming back to the reader with its menu still open starts nothing until the menu closes. */
    @Test
    fun `a session started while held runs from the release`() {
        val clock = ReadSessionClock().apply { hold(held = true, now = at(1_000L)) }
        clock.resume(now = at(2_000L))
        clock.hold(held = false, now = at(7_000L))

        clock.take(now = at(8_000L)) shouldBe 1_000L
    }

    @Test
    fun `releasing a hold with no session starts none`() {
        val clock = ReadSessionClock().apply { hold(held = true, now = at(1_000L)) }
        clock.hold(held = false, now = at(2_000L))

        clock.take(now = at(8_000L)) shouldBe 0L
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)
}
