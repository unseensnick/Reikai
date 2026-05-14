package yokai.data.library.taste

import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import yokai.data.DatabaseHandler
import yokai.domain.library.taste.TrackerLibraryRepository
import yokai.domain.library.taste.model.TrackStatus
import yokai.domain.library.taste.model.TrackedEntry

class TrackerLibraryRepositoryImpl(
    private val handler: DatabaseHandler,
    private val trackManager: TrackManager,
) : TrackerLibraryRepository {

    override suspend fun getLocalTrackedEntries(): List<TrackedEntry> {
        val raw = handler.awaitList {
            tracker_library_cacheQueries.getLocalTrackedEntries(::LocalEntryRow)
        }
        // Layer A reads every tracker's manga_sync rows — two cleanup passes apply:
        //
        // 1. Filter to supported Phase 4 trackers (AniList / MAL / Kitsu). Historical rows
        //    from Shikimori / Bangumi / MangaUpdates / Komga / Kavita / Suwayomi can't dedup
        //    against the fetched data (no malId cross-ref) and would inflate the count.
        //
        // 2. Distinct by (trackerId, remoteId). Y2K's multi-source grouping links the same
        //    tracker entry to multiple `mangas` rows (one per source), so the SQL JOIN
        //    returns N rows for an N-source-grouped manga. Layer A's job is "currently in
        //    library + actively tracked" — that's one signal per tracker entry, not N.
        return raw
            .mapNotNull { row ->
                if (row.trackerId !in SUPPORTED_TASTE_TRACKERS) null else row.toTrackedEntry()
            }
            .distinctBy { it.trackerId to it.remoteId }
    }

    override suspend fun getCachedEntries(trackerId: Long?): List<TrackedEntry> {
        return if (trackerId != null) {
            handler.awaitList {
                tracker_library_cacheQueries.getCachedByTracker(trackerId, ::cachedRowToEntry)
            }
        } else {
            handler.awaitList {
                tracker_library_cacheQueries.getAllCached(::cachedRowToEntry)
            }
        }
    }

    override suspend fun replaceCacheForTracker(trackerId: Long, entries: List<TrackedEntry>) {
        val now = System.currentTimeMillis()
        handler.await(inTransaction = true) {
            tracker_library_cacheQueries.deleteByTracker(trackerId)
            entries.forEach { entry ->
                tracker_library_cacheQueries.insert(
                    trackerId = entry.trackerId,
                    remoteId = entry.remoteId,
                    title = entry.title,
                    score = entry.score,
                    status = entry.status.ordinal.toLong(),
                    tags = entry.tags.joinToString(","),
                    fetchedAt = entry.fetchedAt ?: now,
                    malId = entry.malId,
                    anilistId = entry.anilistId,
                )
            }
        }
    }

    override suspend fun lastFetchedAt(trackerId: Long): Long? {
        val result = handler.awaitOne { tracker_library_cacheQueries.lastFetchedAt(trackerId) }
        return result.takeIf { it > 0L }
    }

    /**
     * Maps a Layer A raw row to a [TrackedEntry], applying per-tracker score + status
     * normalization. Returns `null` when the row's tracker id doesn't match any known
     * [TrackService] (e.g. a removed/legacy tracker).
     *
     * Phase 4 core caveat: only [TrackService.completedStatus] / [TrackService.readingStatus]
     * / [TrackService.planningStatus] are detectable from the base API, so ON_HOLD and
     * DROPPED currently fall through to [TrackStatus.UNKNOWN]. Phase 4.1+ adds dedicated
     * accessors so the compute formula can apply the full status-weight table.
     */
    private fun LocalEntryRow.toTrackedEntry(): TrackedEntry? {
        val service = trackManager.getService(trackerId) ?: return null
        // Layer A self-cross-refs: a tracker's own remote id IS its own cross-tracker id.
        // MAL row: malId = remoteId; AniList row: anilistId = remoteId. Kitsu has no
        // self-cross-ref here (its cross-refs come from the mappings table fetched separately),
        // so Layer A Kitsu rows rely on the borrow-from-Layer-B step in GetTrackedEntries.
        val malId = if (service.id == TrackManager.MYANIMELIST) remoteId else null
        val anilistId = if (service.id == TrackManager.ANILIST) remoteId else null
        return TrackedEntry(
            trackerId = service.id,
            remoteId = remoteId,
            title = title,
            score = normalizeScore(service, rawScore),
            status = mapStatus(service, rawStatus.toInt()),
            tags = parseTags(rawGenre),
            fetchedAt = null,
            malId = malId,
            anilistId = anilistId,
        )
    }

    private fun cachedRowToEntry(
        trackerId: Long,
        remoteId: Long,
        title: String,
        score: Double,
        status: Long,
        tags: String,
        fetchedAt: Long,
        malId: Long?,
        anilistId: Long?,
    ): TrackedEntry = TrackedEntry(
        trackerId = trackerId,
        remoteId = remoteId,
        title = title,
        score = score,
        status = TrackStatus.entries.getOrNull(status.toInt()) ?: TrackStatus.UNKNOWN,
        tags = parseTags(tags),
        fetchedAt = fetchedAt,
        malId = malId,
        anilistId = anilistId,
    )

    /** Per-tracker score normalized to 0..1; raw 0 (unrated) → -1.0. */
    private fun normalizeScore(service: TrackService, rawScore: Double): Double {
        if (rawScore <= 0.0) return -1.0
        val tenPoint = service.get10PointScore(rawScore.toFloat()).toDouble()
        return (tenPoint / 10.0).coerceIn(0.0, 1.0)
    }

    private fun mapStatus(service: TrackService, raw: Int): TrackStatus = when (raw) {
        service.completedStatus() -> TrackStatus.COMPLETED
        service.readingStatus() -> TrackStatus.READING
        service.planningStatus() -> TrackStatus.PLAN_TO_READ
        // TODO(phase4.1): add onHoldStatus()/droppedStatus() to TrackService so ON_HOLD/DROPPED
        //  contribute their doc-spec weights (0.3 / -1.0) to the compute formula. Until then,
        //  they fall through to UNKNOWN which the formula treats as zero-weight.
        else -> TrackStatus.UNKNOWN
    }

    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.splitToSequence(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private data class LocalEntryRow(
        val trackerId: Long,
        val remoteId: Long,
        val title: String,
        val rawStatus: Long,
        val rawScore: Double,
        val rawGenre: String?,
    )

    companion object {
        /** Phase 4 scope — see SettingsLibraryRecommendationsController.LIBRARY_TRACKERS. */
        private val SUPPORTED_TASTE_TRACKERS: Set<Long> = setOf(
            TrackManager.ANILIST,
            TrackManager.MYANIMELIST,
            TrackManager.KITSU,
        )
    }
}
