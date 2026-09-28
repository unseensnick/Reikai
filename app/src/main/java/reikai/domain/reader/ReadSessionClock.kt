package reikai.domain.reader

import java.util.concurrent.atomic.AtomicReference

/**
 * When the chapter on screen began being read, for its history's time read, in both readers. [take]
 * reads and stops the clock in one step, so two history saves that overlap count a session once
 * (mihon 553762fae clears the start before its save awaits; one atomic step leaves no window at all).
 */
class ReadSessionClock {

    private val startedAt = AtomicReference<Long?>(null)

    val isRunning: Boolean
        get() = startedAt.get() != null

    fun start(now: Long) {
        startedAt.set(now)
    }

    /** The session's length up to [now], or 0 when none is running; the clock is stopped after. */
    fun take(now: Long): Long = startedAt.getAndSet(null)?.let { now - it } ?: 0L
}
