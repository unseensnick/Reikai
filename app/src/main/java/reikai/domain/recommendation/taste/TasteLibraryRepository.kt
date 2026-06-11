package reikai.domain.recommendation.taste

/**
 * Persistence for the cached tracker-library snapshot the taste profile is computed from. Written
 * by the tracker-library pull (one tracker at a time), read whole by [GetTasteProfile]. The cache is
 * rebuildable, so it is never the source of truth, only a local mirror to keep recommendations off
 * the network on a details open.
 */
interface TasteLibraryRepository {

    suspend fun getAll(): List<TrackedEntry>

    /** Replace every cached row for [trackerId] with [entries] in one transaction. */
    suspend fun replaceTracker(trackerId: Long, entries: List<TrackedEntry>, fetchedAt: Long)

    suspend fun deleteAll()

    suspend fun count(): Long

    /** Epoch millis of the most recent pull for [trackerId], or null if never pulled. */
    suspend fun lastFetch(trackerId: Long): Long?
}
