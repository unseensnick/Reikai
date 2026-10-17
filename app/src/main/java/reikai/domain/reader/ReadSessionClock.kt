package reikai.domain.reader

import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Instant

/**
 * When the chapter on screen began being read, for its history's time read, in both readers. [take]
 * reads and stops the clock in one step, so two history saves that overlap count a session once
 * (mihon 553762fae clears the start before its save awaits; one atomic step leaves no window at all).
 */
class ReadSessionClock {

    private val startedAt = AtomicReference<Instant?>(null)

    val isRunning: Boolean
        get() = startedAt.get() != null

    fun start(now: Instant) {
        startedAt.set(now)
    }

    /** Starts a session unless one is running, which a reader coming back to the screen keeps: read aloud played on. */
    fun resume(now: Instant) {
        startedAt.compareAndSet(null, now)
    }

    /** The session's length up to [now], or 0 when none is running; the clock is stopped after. */
    fun take(now: Instant): Long = startedAt.getAndSet(null)?.let { (now - it).inWholeMilliseconds } ?: 0L
}
