package reikai.novel.content

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Runs a user's regex on a thread of its own, for at most a time budget. Android's engine (ICU) copies
 * its input and matches in native code, so neither the input's reads nor a coroutine cancellation can
 * stop a pattern that backtracks for hours: the caller leaves it instead, and the thread runs on to its
 * end. While a left thread runs, its pattern answers too slow without starting another. On the JVM the
 * engine reads through [CancellableCharSequence], so there the thread stops too. The rule is in
 * docs/dev/subsystems/novel-reader-rendering.md.
 */
internal class UserRegexRunner(private val threads: Executor) {

    private val poll = 50.milliseconds

    // Left threads still running, counted per pattern and options, since two runs can be left at once.
    private val leftRunning = ConcurrentHashMap<Pair<String, Set<RegexOption>>, Int>()

    /**
     * [block] over an input it reads through [CancellableCharSequence], or null once it runs past [budget]
     * or while an earlier run of [regex] that was left is still going. Throws [CancellationException] as
     * soon as [isActive] turns false, and that run counts as left too.
     */
    fun <T> run(
        regex: Regex,
        budget: Duration,
        isActive: () -> Boolean,
        block: (wrap: (CharSequence) -> CharSequence) -> T,
    ): T? {
        val key = regex.pattern to regex.options
        if (leftRunning.containsKey(key)) return null
        val state = AtomicInteger(RUNNING)
        val answer = CompletableFuture<T>()
        threads.execute {
            try {
                answer.complete(block { CancellableCharSequence(it) { state.get() == RUNNING } })
            } catch (e: Throwable) {
                answer.completeExceptionally(e)
            } finally {
                if (state.getAndSet(FINISHED) == LEFT) release(key)
            }
        }
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            if (!isActive()) {
                leave(key, state)
                throw CancellationException("Regex scan cancelled")
            }
            try {
                return answer.get(poll.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (started.elapsedNow() >= budget) {
                    leave(key, state)
                    return null
                }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    // Counted before the state flips, so a thread finishing in between never releases a count not yet taken.
    private fun leave(key: Pair<String, Set<RegexOption>>, state: AtomicInteger) {
        leftRunning.merge(key, 1, Int::plus)
        if (!state.compareAndSet(RUNNING, LEFT)) release(key)
    }

    private fun release(key: Pair<String, Set<RegexOption>>) {
        leftRunning.computeIfPresent(key) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    companion object {
        private const val RUNNING = 0
        private const val LEFT = 1
        private const val FINISHED = 2

        val shared = UserRegexRunner(
            Executors.newCachedThreadPool { task ->
                Thread(task, "user-regex").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            },
        )
    }
}
