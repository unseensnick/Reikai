package exh.util

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * RK: linear-backoff throttle for E-Hentai favorite writes, ported from Komikku
 * (exh/util/ThrottleManager). Each [throttle] call spaces requests a little further apart
 * (the first is instant, then +[inc] each time) up to a [max] ceiling. The escalation is what
 * keeps a large favorites backfill from tripping E-Hentai's rate limiter; a flat delay would not.
 */
class ThrottleManager(
    private val max: Duration = THROTTLE_MAX,
    private val inc: Duration = THROTTLE_INC,
    // Clock seam: defaults to the wall clock; tests inject a fixed source to assert the escalation.
    private val now: () -> Duration = { System.currentTimeMillis().milliseconds },
) {
    private var lastThrottleTime = Duration.ZERO
    private var throttleTime = Duration.ZERO

    suspend fun throttle() {
        val currentTime = now()
        val timeDiff = currentTime - lastThrottleTime
        if (timeDiff < throttleTime) {
            delay(throttleTime - timeDiff)
        }

        if (throttleTime < max) {
            throttleTime += inc
        }

        lastThrottleTime = now()
    }

    companion object {
        val THROTTLE_MAX = 5.5.seconds
        val THROTTLE_INC = 20.milliseconds
    }
}
