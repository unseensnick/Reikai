package reikai.domain.local

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import reikai.domain.library.ContentType
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One lock per local series, by content type and case-blind folder name, held by whatever lists the series'
 * files or changes them, so a listing and its row sync never run between a move's disk step and its row step.
 * A holder may take a series it holds again inside its block (a move lists the series it moved), since the
 * keys held travel in the coroutine context; a move over several series names them all up front and they are
 * taken in one sorted order, which is what keeps two moves from waiting on each other. Every [withSeries]
 * also waits for the journal replay at start ([open]), which repairs a half-done rename before any listing.
 */
@Inject
@SingleIn(AppScope::class)
class LocalSeriesLocks {

    private val locks = ConcurrentHashMap<Pair<ContentType, String>, Mutex>()
    private val replayed = CompletableDeferred<Unit>()

    suspend fun <T> withSeries(type: ContentType, folder: String, block: suspend () -> T): T =
        withSeries(type, listOf(folder), block)

    suspend fun <T> withSeries(type: ContentType, folders: Collection<String>, block: suspend () -> T): T {
        replayed.await()
        return locked(type, folders, block)
    }

    /** [withSeries] for the replay itself, which runs before [open] and so cannot wait on it. */
    suspend fun <T> locked(type: ContentType, folder: String, block: suspend () -> T): T =
        locked(type, listOf(folder), block)

    suspend fun <T> locked(type: ContentType, folders: Collection<String>, block: suspend () -> T): T {
        val keys = folders.mapTo(sortedSetOf(KEY_ORDER)) { type to it.lowercase() }
        val held = currentCoroutineContext()[HeldSeries]?.keys.orEmpty()
        val wanted = keys - held
        if (wanted.isEmpty()) return block()
        // Taking a series after others are already held is the one way two holders can wait on each other,
        // so it fails here rather than hanging: a move names every series it touches before it starts.
        check(held.isEmpty()) { "Local series ${wanted.map { it.second }} were not named up front" }
        return withContext(HeldSeries(keys)) { acquire(wanted.toList(), 0, block) }
    }

    private suspend fun <T> acquire(keys: List<Pair<ContentType, String>>, index: Int, block: suspend () -> T): T =
        if (index == keys.size) {
            block()
        } else {
            locks.getOrPut(keys[index]) { Mutex() }.withLock { acquire(keys, index + 1, block) }
        }

    /** Lets every waiting [withSeries] go: the journal has been replayed, or there was nothing to replay. */
    fun open() {
        replayed.complete(Unit)
    }

    val isOpen: Boolean get() = replayed.isCompleted

    /** The series the current coroutine holds, so a listing inside a move runs without waiting on it. */
    private class HeldSeries(val keys: Set<Pair<ContentType, String>>) : AbstractCoroutineContextElement(HeldSeries) {
        companion object Key : CoroutineContext.Key<HeldSeries>
    }

    private companion object {
        val KEY_ORDER = compareBy<Pair<ContentType, String>>({ it.first.ordinal }, { it.second })
    }
}
