package yokai.domain.library.taste.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Layer B (cached tracker fetch) wins per `(trackerId, remoteId)` when present —
 * accurate DROPPED/ON_HOLD from each tracker's API; Layer A (local `manga_sync` row)
 * is the fallback when the cache is empty or missing the entry, mapped through the
 * tracker service's status accessors. Layer A currently collapses DROPPED + ON_HOLD
 * into [TrackStatus.UNKNOWN] (see Phase 4.1 TODO at `TrackerLibraryRepositoryImpl.mapStatus`).
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

        // Parallel per-favorite track lookup. Runs once per MangaDetailsPresenter instance
        // (one-shot via `relatedMangasFetched`), so N=1000 favorites with async is fine.
        val perFavorite = favorites.map { manga ->
            async {
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
        else -> TrackStatus.UNKNOWN
    }
}
