package reikai.novel.content

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Runs a user's regex on a thread of its own, for at most a time budget. Android's engine (ICU) copies
 * its input and matches in native code, so neither the input's reads nor a coroutine cancellation can
 * stop a pattern that backtracks for hours: the caller leaves it instead, and the thread runs on to its
 * end. On the JVM the engine reads through [CancellableCharSequence], so there the thread stops too. The
 * rule is in docs/dev/subsystems/novel-reader-rendering.md.
 */
internal object UserRegexRunner {

    private val threads = Executors.newCachedThreadPool { task ->
        Thread(task, "user-regex").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val poll = 50.milliseconds

    /**
     * [block] over an input it reads through [CancellableCharSequence], or null once it runs past
     * [budget]. Throws [CancellationException] as soon as [isActive] turns false.
     */
    fun <T> run(budget: Duration, isActive: () -> Boolean, block: (wrap: (CharSequence) -> CharSequence) -> T): T? {
        val future = threads.submit<T> {
            val thread = Thread.currentThread()
            block { CancellableCharSequence(it) { !thread.isInterrupted } }
        }
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            if (!isActive()) {
                future.cancel(true)
                throw CancellationException("Regex scan cancelled")
            }
            try {
                return future.get(poll.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (started.elapsedNow() >= budget) {
                    future.cancel(true)
                    return null
                }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }
}
