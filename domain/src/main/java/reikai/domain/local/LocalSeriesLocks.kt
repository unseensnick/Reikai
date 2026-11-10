package reikai.domain.local

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import reikai.domain.library.ContentType
import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per local series, by content type and folder name, held by whatever lists the series' files or
 * changes them, so a listing never runs between a rename's disk step and its row step. The folder name is
 * compared case-blind, as the disk finds it. Not reentrant: a holder that lists the series through its
 * source again deadlocks, so a relist runs after the lock is released. Every [withSeries] also waits for the
 * journal replay at start ([open]), which repairs a half-done rename before any listing can read it.
 */
@Inject
@SingleIn(AppScope::class)
class LocalSeriesLocks {

    private val locks = ConcurrentHashMap<Pair<ContentType, String>, Mutex>()
    private val replayed = CompletableDeferred<Unit>()

    suspend fun <T> withSeries(type: ContentType, folder: String, block: suspend () -> T): T {
        replayed.await()
        return locked(type, folder, block)
    }

    /** [withSeries] for the replay itself, which runs before [open] and so cannot wait on it. */
    suspend fun <T> locked(type: ContentType, folder: String, block: suspend () -> T): T =
        locks.getOrPut(type to folder.lowercase()) { Mutex() }.withLock { block() }

    /** Lets every waiting [withSeries] go: the journal has been replayed, or there was nothing to replay. */
    fun open() {
        replayed.complete(Unit)
    }

    val isOpen: Boolean get() = replayed.isCompleted
}
