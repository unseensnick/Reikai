package yokai.domain.library.taste

import yokai.domain.library.taste.model.TrackedEntry

/**
 * Phase 4 taste-profile storage.
 *
 * Two data layers are exposed through the same [TrackedEntry] shape:
 * - Layer A ([getLocalTrackedEntries]) — live JOIN of `manga_sync ⋈ mangas`; no
 *   network, always fresh, scoped to library entries with a Track row.
 * - Layer B ([getCachedEntries] / [replaceCacheForTracker]) — persisted per-tracker
 *   library fetches; survives backups and catches "rate-then-remove" entries.
 */
interface TrackerLibraryRepository {

    /** Layer A: every Track row attached to a library manga, normalized to [TrackedEntry]. */
    suspend fun getLocalTrackedEntries(): List<TrackedEntry>

    /**
     * Layer B: read cached entries. Pass `null` to get all trackers' cached rows,
     * or a specific tracker id (matches [eu.kanade.tachiyomi.data.track.TrackService.id]).
     */
    suspend fun getCachedEntries(trackerId: Long? = null): List<TrackedEntry>

    /**
     * Layer B: atomically replace one tracker's cache. Deletes prior rows for [trackerId],
     * then bulk-inserts [entries], all inside a single transaction so a partial failure
     * leaves the previous data intact.
     */
    suspend fun replaceCacheForTracker(trackerId: Long, entries: List<TrackedEntry>)

    /** Epoch millis of the most recent fetch for [trackerId], or `null` if never fetched. */
    suspend fun lastFetchedAt(trackerId: Long): Long?
}
