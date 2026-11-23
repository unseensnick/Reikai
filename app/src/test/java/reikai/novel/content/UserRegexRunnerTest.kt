package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * The time budget that lets a user regex be left, since Android's engine matches natively and cannot be
 * stopped. A JVM test cannot run ICU, so the slow block here is one no input read can stop either. Real time
 * stays: the runner is a blocking wait on a thread of its own, timed on the monotonic clock.
 */
class UserRegexRunnerTest {

    @Test
    fun `a run past its budget is left with no answer`() {
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            UserRegexRunner.run(100.milliseconds, isActive = { true }) { Thread.sleep(60_000) }
        } shouldBe null
    }

    @Test
    fun `a cancelled caller leaves a run at once`() {
        assertThrows<CancellationException> {
            UserRegexRunner.run(60_000.milliseconds, isActive = { false }) { Thread.sleep(60_000) }
        }
    }

    @Test
    fun `a caller cancelled while the run is under way leaves it`() {
        val underWay = AtomicBoolean(false)

        assertThrows<CancellationException> {
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                UserRegexRunner.run(60_000.milliseconds, isActive = { !underWay.get() }) {
                    underWay.set(true)
                    Thread.sleep(60_000)
                }
            }
        }
    }

    @Test
    fun `a run within its budget answers`() {
        UserRegexRunner.run(5_000.milliseconds, isActive = {
            true
        }) { wrap -> Regex("b").findAll(wrap("abcb")).count() } shouldBe
            2
    }
}
