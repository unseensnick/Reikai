package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The time budget that lets a user regex be left, since Android's engine matches natively and cannot be
 * stopped. A JVM test cannot run ICU, so the slow block here is one no input read can stop either. Real time
 * stays: the runner is a blocking wait on a thread of its own, timed on the monotonic clock. The left-thread
 * cases hold the run on [HeldThreads], so the test decides when a left thread finishes.
 */
class UserRegexRunnerTest {

    private val runner = UserRegexRunner.shared

    @Test
    fun `a run past its budget is left with no answer`() {
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            runner.run(Regex("budget"), 100.milliseconds, isActive = { true }) { Thread.sleep(60_000) }
        } shouldBe null
    }

    @Test
    fun `a cancelled caller leaves a run at once`() {
        assertThrows<CancellationException> {
            runner.run(Regex("cancelled"), 60_000.milliseconds, isActive = { false }) { Thread.sleep(60_000) }
        }
    }

    @Test
    fun `a caller cancelled while the run is under way leaves it`() {
        val underWay = AtomicBoolean(false)

        assertThrows<CancellationException> {
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                runner.run(Regex("under way"), 60_000.milliseconds, isActive = { !underWay.get() }) {
                    underWay.set(true)
                    Thread.sleep(60_000)
                }
            }
        }
    }

    @Test
    fun `a run within its budget answers`() {
        runner.run(Regex("b"), 5_000.milliseconds, isActive = {
            true
        }) { wrap -> Regex("b").findAll(wrap("abcb")).count() } shouldBe
            2
    }

    /** Running it again would start another thread that cannot be stopped either. */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a pattern whose left run still goes on answers too slow without running again`(cancelled: Boolean) {
        val threads = HeldThreads()
        val runner = UserRegexRunner(threads)
        leave(runner, Regex("a"), cancelled)

        runner.run(Regex("a"), 60_000.milliseconds, isActive = { true }) { "ran" } shouldBe null
        threads.held.size shouldBe 1
    }

    @Test
    fun `a pattern runs again once its left run has finished`() {
        val threads = HeldThreads()
        val runner = UserRegexRunner(threads)
        leave(runner, Regex("a"), cancelled = false)

        threads.release()

        runner.run(Regex("a"), 60_000.milliseconds, isActive = { true }) { "ran" } shouldBe "ran"
    }

    @Test
    fun `the same pattern with other options is not held back`() {
        val threads = HeldThreads()
        val runner = UserRegexRunner(threads)
        leave(runner, Regex("a"), cancelled = false)
        threads.hold = false

        runner.run(Regex("a", RegexOption.IGNORE_CASE), 60_000.milliseconds, isActive = { true }) { "ran" } shouldBe
            "ran"
    }

    private fun leave(runner: UserRegexRunner, regex: Regex, cancelled: Boolean) {
        if (cancelled) {
            assertThrows<CancellationException> { runner.run(regex, 60_000.milliseconds, isActive = { false }) { } }
        } else {
            runner.run(regex, 1.nanoseconds, isActive = { true }) { } shouldBe null
        }
    }

    /** Keeps each run until [release], then runs the rest at once on the caller's thread. */
    private class HeldThreads : Executor {
        val held = mutableListOf<Runnable>()
        var hold = true

        override fun execute(command: Runnable) {
            if (hold) held += command else command.run()
        }

        fun release() {
            hold = false
            held.forEach { it.run() }
        }
    }
}
