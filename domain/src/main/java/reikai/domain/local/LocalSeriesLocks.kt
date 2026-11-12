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
 * taken in one sorted order, which keeps two moves from waiting on each other. Naming a series takes every folder
 * of its group ([LocalGroups.closure]); every [withSeries] waits for the journal replay at start ([open]).
 */
@SingleIn(AppScope::class)
class LocalSeriesLocks @Inject constructor(private val owners: LocalVolumeOwnerRepository) {

    /** Locks with no grouping, every series its own folder only: for tests that do not group volumes. */
    constructor() : this(NoOwners)

    private val locks = ConcurrentHashMap<Pair<ContentType, String>, Mutex>()
    private val replayed = CompletableDeferred<Unit>()

    suspend fun <T> withSeries(type: ContentType, folder: String, block: suspend () -> T): T =
        withSeries(type, listOf(folder), block)

    suspend fun <T> withSeries(type: ContentType, folders: Collection<String>, block: suspend () -> T): T {
        replayed.await()
        return locked(type, folders, block)
    }

    /** [withSeries] for the replay itself, which runs before [open] and so cannot wait on it. */
    suspend fun <T> locked(type: ContentType, folders: Collection<String>, block: suspend () -> T): T {
        val held = currentCoroutineContext()[HeldSeries]?.keys.orEmpty()
        while (true) {
            val keys = keysOf(type, folders)
            val repairing = currentCoroutineContext()[Repairing] != null
            if (isOpen && !repairing &&
                keys.any { it in blocked }
            ) {
                throw LocalSeriesBlockedException(keys.map { it.second })
            }
            val wanted = keys - held
            if (wanted.isEmpty()) return block()
            // Taking a series after others are already held is the one way two holders can wait on each other,
            // so it fails here rather than hanging: a move names every series it touches before it starts.
            check(held.isEmpty()) { "Local series ${wanted.map { it.second }} were not named up front" }
            // A group that grew while this waited (a combine took a folder in) is taken again whole.
            val got = withContext(HeldSeries(keys)) {
                acquire(wanted.toList(), 0) { if (keysOf(type, folders) == keys) Held(block()) else null }
            }
            if (got != null) return got.value
        }
    }

    // Every folder of each named series' group, since a group lists across its folders.
    private suspend fun keysOf(type: ContentType, folders: Collection<String>) =
        LocalGroups(owners.all(type)).closure(folders).mapTo(sortedSetOf(KEY_ORDER)) { type to it.lowercase() }

    private class Held<T>(val value: T)

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

    private val blocked = ConcurrentHashMap.newKeySet<Pair<ContentType, String>>()

    /**
     * Refuses every later hold on [folders] in this process: their files and rows disagree after an organize that
     * could neither finish nor be put back, and a listing would read that as chapters gone. The journal keeps the
     * organize, which a retry in this process ([repairing]) or the next start's replay finishes or reverses.
     */
    fun block(type: ContentType, folders: Collection<String>) {
        folders.forEach { blocked += type to it.lowercase() }
    }

    /** Lets [folders] be held again, once the organize that blocked them has been finished or put back. */
    fun unblock(type: ContentType, folders: Collection<String>) {
        folders.forEach { blocked -= type to it.lowercase() }
    }

    /** [locked] for the repair of a blocked organize, the one holder its own block lets through. */
    suspend fun <T> repairing(type: ContentType, folders: Collection<String>, block: suspend () -> T): T =
        withContext(Repairing()) { locked(type, folders, block) }

    private class Repairing : AbstractCoroutineContextElement(Repairing) {
        companion object Key : CoroutineContext.Key<Repairing>
    }

    /** The series the current coroutine holds, so a listing inside a move runs without waiting on it. */
    private class HeldSeries(val keys: Set<Pair<ContentType, String>>) : AbstractCoroutineContextElement(HeldSeries) {
        companion object Key : CoroutineContext.Key<HeldSeries>
    }

    private companion object {
        val KEY_ORDER = compareBy<Pair<ContentType, String>>({ it.first.ordinal }, { it.second })
    }
}

/** Local series whose files and rows disagree until the next start's journal replay ([LocalSeriesLocks.block]). */
class LocalSeriesBlockedException(
    folders: List<String>,
) : IllegalStateException("Local series $folders wait for a repair")

private object NoOwners : LocalVolumeOwnerRepository {
    override suspend fun all(type: ContentType): List<VolumeOwner> = emptyList()
}

/**
 * [block] for [entry] under the lock of its address as it stands once that lock is held. A rename that landed while
 * the caller waited (Organize files, a relink) moved the address, so the entry is read again and locked under the
 * new one; both local sources' refreshes call this, so neither lists a folder that is gone.
 */
suspend fun <E, T> lockedAtCurrentAddress(
    entry: E,
    address: (E) -> String,
    reread: suspend (E) -> E,
    lock: suspend (String, suspend () -> Any?) -> Any?,
    block: suspend (E) -> T,
): T {
    var held = entry
    while (true) {
        val outcome = lock(address(held)) {
            val current = reread(held)
            if (address(current) == address(held)) AddressLock.Ran(block(current)) else AddressLock.Moved(current)
        }
        @Suppress("UNCHECKED_CAST")
        when (outcome) {
            is AddressLock.Ran<*> -> return outcome.value as T
            is AddressLock.Moved<*> -> held = outcome.entry as E
        }
    }
}

private sealed interface AddressLock {
    class Ran<T>(val value: T) : AddressLock
    class Moved<E>(val entry: E) : AddressLock
}
