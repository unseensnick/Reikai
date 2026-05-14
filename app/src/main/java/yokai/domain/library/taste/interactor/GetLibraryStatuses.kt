package yokai.domain.library.taste.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import yokai.domain.library.taste.TrackerLibraryRepository
import yokai.domain.library.taste.model.TrackStatus
import yokai.domain.manga.interactor.GetManga
import yokai.domain.track.interactor.GetTrack

/**
 * Phase 7 — build a `(sourceId, url) → Set<TrackStatus>` map for every library favorite.
 *
 * Used by [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter] to decide which library
 * candidates the user wants filtered out of the related-mangas pool based on tracker
 * status (Reading/Completed/Dropped). Untracked favorites map to an empty set so the
 * caller can distinguish "in library, no tracker info" from "not in library at all."
 *
 * Status sourcing follows the same two-layer composition as [GetTrackedEntries]:
 * Layer B (cached tracker fetch) wins per `(trackerId, remoteId)` when present;
 * Layer A (local `manga_sync` row) is the fallback when the cache is empty or
 * missing the entry, mapped through the tracker service's status accessors
 * (Phase 4.1 added `onHoldStatus()` / `droppedStatus()` so all six full-feature
 * trackers — AniList, MAL, MangaUpdates, Kitsu, Shikimori, Bangumi — classify
 * DROPPED / ON_HOLD correctly from local rows alone).
 *
 * Pure I/O composition — no scoring, no preferences, no filter policy. The presenter
 * owns those decisions.
 */
class GetLibraryStatuses(
    private val getManga: GetManga,
    private val getTrack: GetTrack,
    private val trackerLibraryRepository: TrackerLibraryRepository,
    private val trackManager: TrackManager,
) {

    suspend fun await(): Map<Pair<Long, String>, Set<TrackStatus>> = coroutineScope {
        val favorites = getManga.awaitFavorites()
        if (favorites.isEmpty()) return@coroutineScope emptyMap()

        // Cache built once, consulted N times by the per-favorite resolver below.
        val cachedByKey: Map<Pair<Long, Long>, TrackStatus> = runCatching {
            trackerLibraryRepository.getCachedEntries()
        }.getOrElse {
            Logger.e(it) { "GetLibraryStatuses: cached entries lookup failed, falling back to local-only" }
            emptyList()
        }.associate { (it.trackerId to it.remoteId) to it.status }

        // Parallel per-favorite track lookup, bounded by a Semaphore so a 1000-favorite library
        // doesn't fire 1000 concurrent DB queries at the IO dispatcher. 16 in-flight permits
        // keeps SQLDelight's connection pool happy without throttling small libraries.
        val concurrency = Semaphore(MAX_PARALLEL_TRACK_LOOKUPS)
        val perFavorite = favorites.map { manga ->
            async {
                concurrency.withPermit {
                    val tracks = runCatching { getTrack.awaitAllByMangaId(manga.id) }.getOrElse { emptyList() }
                    val statuses: Set<TrackStatus> = tracks.mapNotNullTo(HashSet()) { track ->
                        val key = track.sync_id to track.media_id
                        cachedByKey[key] ?: run {
                            val service = trackManager.getService(track.sync_id) ?: return@mapNotNullTo null
                            mapStatus(service, track.status)
                        }
                    }
                    Triple(manga.source, manga.url, statuses)
                }
            }
        }.awaitAll()

        val result = HashMap<Pair<Long, String>, Set<TrackStatus>>(perFavorite.size)
        for ((source, url, statuses) in perFavorite) {
            // Merge across multi-source grouping: same (source, url) shouldn't double-key,
            // but if it does (e.g. two favorites pointing at the same source URL), union.
            result.merge(source to url, statuses) { a, b -> a + b }
        }
        result
    }

    private fun mapStatus(service: TrackService, raw: Int): TrackStatus = when (raw) {
        service.completedStatus() -> TrackStatus.COMPLETED
        service.readingStatus() -> TrackStatus.READING
        service.planningStatus() -> TrackStatus.PLAN_TO_READ
        service.onHoldStatus() -> TrackStatus.ON_HOLD
        service.droppedStatus() -> TrackStatus.DROPPED
        else -> TrackStatus.UNKNOWN
    }

    companion object {
        /** Caps in-flight `getTrack.awaitAllByMangaId` calls; balances IO parallelism against
         *  SQLDelight's connection-pool contention. */
        private const val MAX_PARALLEL_TRACK_LOOKUPS = 16
    }
}
