package reikai.presentation.reader.text

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads under way in the background, by key, so a load asked for directly can wait for one already
 * running instead of fetching the same thing a second time beside it.
 */
internal class InFlightLoads<K : Any> {

    private val running = ConcurrentHashMap<K, CompletableDeferred<Unit>>()

    /** False when [key] is already loading, in which case the caller starts nothing. */
    fun begin(key: K): Boolean = running.putIfAbsent(key, CompletableDeferred()) == null

    /** The load of [key] ended, however it ended. */
    fun finish(key: K) {
        running.remove(key)?.complete(Unit)
    }

    /** Returns once no load of [key] is running, at once when none was. */
    suspend fun awaitIdle(key: K) {
        running[key]?.await()
    }
}
