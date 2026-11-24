package reikai.domain.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A reader's progress writes, which outlive it on [scope], landing in the order they were launched. The
 * lock is taken before a write's first suspension and the mutex is fair, so a write that suspends longer
 * cannot let a later page's land first. Both readers save through one, so they keep the same order.
 */
class ReaderProgressWrites(private val scope: CoroutineScope) {

    private val lock = Mutex()

    /** Launches [write] behind every write launched before it, from any thread: undispatched, so the lock is
     *  taken (or queued for) inside this call, and the write itself then runs on the main thread. */
    fun launch(write: suspend () -> Unit): Job =
        scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            lock.withLock { withContext(Dispatchers.Main.immediate) { write() } }
        }

    /** Returns once every write launched so far has landed, as the pending deletion on leaving needs. */
    suspend fun awaitLanded() = lock.withLock {}
}
