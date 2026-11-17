package reikai.domain.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * The reader's position, held back until the reader settles on it so a burst of page turns or scroll
 * reports writes once. A report for another chapter writes the held one first, since the step would
 * otherwise drop it. The host flushes on pause, and [scope] ending writes what is still held, since a
 * position can be reported after the pause (read aloud plays on in the background).
 */
class ReaderProgressSave<K : Any, V>(
    private val scope: CoroutineScope,
    private val write: (K, V) -> Unit,
) {

    private val held = AtomicReference<Pair<K, V>?>(null)

    @Volatile
    private var delayed: Job? = null

    /** Holds [value] for [key], written after [PROGRESS_SAVE_DELAY], or at once when [now]: a finish carries
     *  mark-as-read and the tracker push, so it never waits. */
    fun report(key: K, value: V, now: Boolean = false) {
        delayed?.cancel()
        val previous = held.getAndSet(key to value)
        if (previous != null && previous.first != key) write(previous.first, previous.second)
        if (now) {
            flush()
        } else {
            // Undispatched, so it is already in the delay when a close cancels it, so the catch falls through to the write.
            delayed = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    delay(PROGRESS_SAVE_DELAY)
                } catch (e: CancellationException) {
                    // A later report, a flush or a discard ended the wait; only the reader closing writes.
                    if (scope.isActive) throw e
                }
                flush()
            }
        }
    }

    /** The position held for [key], which is newer than anything stored for it. */
    fun heldFor(key: K): V? = held.get()?.takeIf { it.first == key }?.second

    /** Writes the held position now, if there is one. */
    fun flush() {
        delayed?.cancel()
        val (key, value) = held.getAndSet(null) ?: return
        write(key, value)
    }

    /** Drops the held position unwritten when its key matches, for one a later write supersedes. */
    fun discardIf(predicate: (K) -> Boolean) {
        val current = held.get() ?: return
        if (predicate(current.first) && held.compareAndSet(current, null)) delayed?.cancel()
    }
}

/** Long enough to span a quick run of page turns, short enough that a kill rarely loses a position. */
val PROGRESS_SAVE_DELAY = 500.milliseconds
