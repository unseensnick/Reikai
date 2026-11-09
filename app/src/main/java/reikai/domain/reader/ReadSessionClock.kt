package reikai.domain.reader

import kotlin.time.Instant

/**
 * When the chapter on screen began being read, for its history's time read, in both readers. [take]
 * reads and stops the clock in one step, so two history saves that overlap count a session once
 * (mihon 553762fae clears the start before its save awaits; one locked step leaves no window at all).
 * A [hold] (the reader's menu open) banks the time so far and counts nothing until it is released.
 */
class ReadSessionClock {

    private var isActive = false
    private var runningSince: Instant? = null
    private var bankedMs = 0L
    private var isHeld = false

    @Synchronized
    fun start(now: Instant) {
        isActive = true
        bankedMs = 0L
        runningSince = if (isHeld) null else now
    }

    /** Starts a session unless one is running, which a reader coming back to the screen keeps: read aloud played on. */
    @Synchronized
    fun resume(now: Instant) {
        if (!isActive) start(now)
    }

    /** While [held], the session counts nothing; releasing it runs the session on, if one is open. */
    @Synchronized
    fun hold(held: Boolean, now: Instant) {
        if (held == isHeld) return
        isHeld = held
        if (held) {
            runningSince?.let { bankedMs += (now - it).inWholeMilliseconds }
            runningSince = null
        } else if (isActive) {
            runningSince = now
        }
    }

    /** The session's length up to [now], or 0 when none is running; the clock is stopped after. */
    @Synchronized
    fun take(now: Instant): Long {
        val total = bankedMs + (runningSince?.let { (now - it).inWholeMilliseconds } ?: 0L)
        isActive = false
        runningSince = null
        bankedMs = 0L
        return total
    }
}
