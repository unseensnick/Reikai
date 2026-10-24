package reikai.domain.library

import reikai.domain.entry.EntryId
import reikai.domain.merge.EntryMergeManager
import reikai.domain.track.source.SourceTrackerDispatcher
import kotlin.time.Instant

/**
 * Taking entries out of the library, one sequence for both content types and every surface, in the
 * order of Mihon's details heart: one all-or-nothing favourite write, then the covers. Each leaving entry first gets its own copy of its group's shared tracker, since the
 * hand-out skips non-favourites. Manage sources splits the steps around its Undo window: [takeOut]
 * at once, then [restore] or [dropCovers] when the window closes.
 */
abstract class EntryLibraryRemoval(
    private val mergeManager: EntryMergeManager,
    private val sourceTracker: SourceTrackerDispatcher,
) {

    /** Takes [ids] out of the library and drops their covers, answering the ids that left. */
    suspend fun await(ids: List<Long>): List<Long> = takeOut(ids).keys.toList().also { dropCovers(it) }

    /**
     * Takes those of [ids] still in the library out in one write, answering when each had joined it
     * (what [restore] puts back), or nothing when the write failed.
     */
    suspend fun takeOut(ids: List<Long>): Map<Long, Instant> {
        val joined = ids.distinct().mapNotNull { id -> favoriteAt(id)?.let { id to it } }.toMap()
        if (joined.isEmpty()) return emptyMap()
        mergeManager.handOutTrackersBeforeRemoval(joined.keys.toList())
        return joined.takeIf { write(it.mapValues { null }) }.orEmpty()
    }

    /** Puts entries [takeOut] removed back in the library with the date each had joined it. */
    suspend fun restore(joined: Map<Long, Instant>) {
        write(joined)
    }

    suspend fun dropCovers(ids: List<Long>) {
        ids.forEach { deleteCovers(it) }
    }

    private suspend fun write(favoriteAt: Map<Long, Instant?>): Boolean = writeFavoriteAt(favoriteAt).also { written ->
        if (written) {
            favoriteAt.forEach { (id, at) ->
                sourceTracker.favoriteChanged(entryId(id), favorite = at != null)
            }
        }
    }

    protected abstract fun entryId(id: Long): EntryId

    /** When the stored row joined the library, null when it is not in it or is gone. */
    protected abstract suspend fun favoriteAt(id: Long): Instant?

    /** Writes every library date in [favoriteAt] or none of them. */
    protected abstract suspend fun writeFavoriteAt(favoriteAt: Map<Long, Instant?>): Boolean

    /**
     * Deletes the cached and custom covers without a new cover key, so the cover on screen does not
     * reload; the key leaves out a custom cover once the entry is out of the library.
     */
    protected abstract suspend fun deleteCovers(id: Long)
}
