package exh.util

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The favorite-write throttle spaces successive requests further apart (instant, then +inc each call)
 * up to a ceiling. Freezing the clock at zero isolates that escalation: each call's delay is exactly
 * the accumulated throttle time, which runTest's virtual clock advances and we read back.
 */
class ThrottleManagerTest {

    private fun manager() = ThrottleManager(
        max = 60.milliseconds,
        inc = 20.milliseconds,
        now = { Duration.ZERO },
    )

    @Test
    fun `the first throttle does not delay`() = runTest {
        manager().throttle()

        testScheduler.currentTime shouldBe 0L
    }

    @Test
    fun `successive throttles escalate the delay by the increment`() = runTest {
        val throttle = manager()

        throttle.throttle() // +0ms
        throttle.throttle() // +20ms
        throttle.throttle() // +40ms

        testScheduler.currentTime shouldBe 60L
    }

    @Test
    fun `the escalating delay is capped at the maximum`() = runTest {
        val throttle = manager()
        throttle.throttle() // +0ms
        throttle.throttle() // +20ms
        throttle.throttle() // +40ms
        throttle.throttle() // +60ms (throttle time now at the 60ms ceiling)
        val beforeCapped = testScheduler.currentTime

        throttle.throttle()

        testScheduler.currentTime - beforeCapped shouldBe 60L
    }
}
