package eu.kanade.tachiyomi.data.track.myanimelist

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.track.library.TrackerLibraryFetcher
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryItem
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryListStatus
import yokai.domain.library.taste.model.TrackStatus
import yokai.domain.library.taste.model.TrackedEntry

/**
 * Pulls the user's full MAL manga list via the cursor-paginated `/users/@me/mangalist`
 * endpoint and normalizes each entry into a [TrackedEntry] for the taste-profile cache.
 *
 * Score: MAL uses a fixed 0..10 integer scale; we divide by 10 and clamp to 0..1. Raw 0
 * (unrated) becomes -1.0 so the compute formula can distinguish "rated low" from "no rating".
 *
 * Status: MAL exposes `is_rereading` as a separate boolean from `status`. When `is_rereading`
 * is true, we override to READING regardless of the declared status — matches AniList's
 * REPEATING → READING rule so re-reading contributes the same engaged-reading signal across
 * trackers.
 *
 * Tags: MAL's `genres[]` is the only per-entry tag-like field exposed inline; MAL has no
 * AniList-style `tags{name,rank}` array, so the taste signal is somewhat coarser than
 * AniList's. Acceptable for v1.
 */
class MyAnimeListLibraryFetcher(
    private val mal: MyAnimeList,
) : TrackerLibraryFetcher {

    override val tracker: MyAnimeList = mal

    override suspend fun fetchLibrary(): List<TrackedEntry> {
        val entries = mal.getUserLibrary()
        val now = System.currentTimeMillis()
        val mapped = entries.map { it.toTrackedEntry(now) }
        val statusCounts = mapped.groupingBy { it.status }.eachCount()
        Logger.d {
            "MyAnimeListLibraryFetcher: fetched ${mapped.size} entries, status=$statusCounts"
        }
        return mapped
    }

    private fun MALLibraryItem.toTrackedEntry(fetchedAt: Long): TrackedEntry {
        return TrackedEntry(
            trackerId = mal.id,
            remoteId = node.id,
            title = node.title,
            score = normalizeScore(listStatus.score),
            status = mapStatus(listStatus),
            tags = node.genres
                .map { it.name.replace(",", "").trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            fetchedAt = fetchedAt,
            // MAL's remote id IS the MAL id by definition — populate so cross-tracker dedup
            // collapses AniList entries that point here via Media.idMal.
            malId = node.id,
        )
    }

    private fun normalizeScore(score: Int): Double {
        if (score <= 0) return -1.0
        return (score / 10.0).coerceIn(0.0, 1.0)
    }

    private fun mapStatus(listStatus: MALLibraryListStatus): TrackStatus {
        if (listStatus.isRereading) return TrackStatus.READING
        return when (listStatus.status) {
            "reading" -> TrackStatus.READING
            "completed" -> TrackStatus.COMPLETED
            "on_hold" -> TrackStatus.ON_HOLD
            "dropped" -> TrackStatus.DROPPED
            "plan_to_read" -> TrackStatus.PLAN_TO_READ
            else -> TrackStatus.UNKNOWN
        }
    }
}
