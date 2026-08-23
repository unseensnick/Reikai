package reikai.domain.track

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

/**
 * Reads the trackers bound anywhere in an entry's merge group, one row per tracker, for either content
 * type. A tracker bound on one source of a merged series has to count while reading or displaying any
 * other source, so tracking spans the group rather than the single entry the per-type repositories read.
 *
 * The engines stay split (manga rows live in `manga_sync`, novels in `novel_tracks`), so the per-type halves
 * come in as lambdas: the group resolver, the single-entry read, and the two fields the shared rule needs.
 */
class GroupTrackReader<T>(
    private val sharingEnabled: () -> Boolean,
    private val relatedIds: suspend (Long) -> List<Long>,
    private val readOne: suspend (Long) -> List<T>,
    private val observeOne: (Long) -> Flow<List<T>>,
    private val trackerId: (T) -> Long,
    private val lastChapterRead: (T) -> Double,
) {

    suspend fun await(entryId: Long): List<T> =
        canonical(trackGroupIds(entryId, sharingEnabled, relatedIds).flatMap { readOne(it) })

    /**
     * Reactive [await]. Emits the entry's own tracks first, so a tracking icon shows without waiting on
     * the group lookup (which loads the membership table), then refines to the whole group.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribe(entryId: Long): Flow<List<T>> =
        flow { emit(trackGroupIds(entryId, sharingEnabled, relatedIds)) }
            .onStart { emit(listOf(entryId)) }
            .distinctUntilChanged()
            .flatMapLatest { groupIds ->
                combine(groupIds.map { observeOne(it) }) { canonical(it.toList().flatten()) }
            }

    private fun canonical(tracks: List<T>): List<T> =
        canonicalTracksPerTracker(tracks, trackerId, lastChapterRead)
}

/**
 * The entries a tracker operation spans: the whole merge group, or just [entryId] when the user has turned
 * tracker sharing off, which makes every source track on its own again.
 */
suspend fun trackGroupIds(
    entryId: Long,
    sharingEnabled: () -> Boolean,
    relatedIds: suspend (Long) -> List<Long>,
): List<Long> = if (sharingEnabled()) relatedIds(entryId) else listOf(entryId)

/**
 * A group can hold several rows for the same tracker: copies left behind by an earlier split, or two
 * already-tracked entries merged together. The copies are not kept in step, because a progress push writes
 * back only to the row it read. Keeping the furthest-read one means a stale copy can never drive the
 * "is this tracker behind?" guard that decides what to push, which would otherwise send the remote service
 * backwards. Ties keep the group's own member order.
 */
fun <T> canonicalTracksPerTracker(
    tracks: List<T>,
    trackerId: (T) -> Long,
    lastChapterRead: (T) -> Double,
): List<T> = tracks.groupBy(trackerId).values.map { rows -> rows.maxBy(lastChapterRead) }
