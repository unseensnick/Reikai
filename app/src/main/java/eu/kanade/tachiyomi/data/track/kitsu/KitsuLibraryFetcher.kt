package eu.kanade.tachiyomi.data.track.kitsu

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import eu.kanade.tachiyomi.data.track.library.TrackerLibraryFetcher
import yokai.domain.library.taste.model.TrackStatus
import yokai.domain.library.taste.model.TrackedEntry

/**
 * Pulls the user's full Kitsu manga library via the JSON:API `/library-entries` endpoint
 * (paginated through `links.next`) and normalizes each entry into a [TrackedEntry] for the
 * taste-profile cache.
 *
 * Score: Kitsu's `ratingTwenty` is a 1..20 integer regardless of the user's display preference
 * (simple/regular/advanced), so we divide by 20 and clamp to 0..1. Missing or zero ratings
 * become -1.0 so the compute formula can distinguish "rated low" from "no rating".
 *
 * Status: Kitsu uses `current` (rather than AniList's `CURRENT` or MAL's `reading`) and
 * `planned` (rather than `plan_to_read`). Otherwise the closed set matches the other trackers.
 * Kitsu has no "rereading" flag exposed at the library-entries level, so all currently-reading
 * entries land in READING regardless of completion history — close enough for v1.
 *
 * Tags: Kitsu's `categories` (the modern equivalent of the deprecated `genres` relationship)
 * are resolved JSON:API-style during the API call and arrive here as a plain list of titles.
 */
class KitsuLibraryFetcher(
    private val kitsu: Kitsu,
) : TrackerLibraryFetcher {

    override val tracker: Kitsu = kitsu

    override suspend fun fetchLibrary(): List<TrackedEntry> {
        val entries = kitsu.getUserLibrary()
        val now = System.currentTimeMillis()
        val mapped = entries.map { it.toTrackedEntry(now) }
        val statusCounts = mapped.groupingBy { it.status }.eachCount()
        Logger.d {
            "KitsuLibraryFetcher: fetched ${mapped.size} entries, status=$statusCounts"
        }
        return mapped
    }

    private fun KitsuLibraryEntry.toTrackedEntry(fetchedAt: Long): TrackedEntry {
        return TrackedEntry(
            trackerId = kitsu.id,
            remoteId = mangaId,
            title = title,
            score = normalizeScore(ratingTwenty),
            status = mapStatus(status),
            tags = tags
                .map { it.replace(",", "").trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            fetchedAt = fetchedAt,
            malId = malId,
            anilistId = anilistId,
        )
    }

    private fun normalizeScore(ratingTwenty: Int?): Double {
        if (ratingTwenty == null || ratingTwenty <= 0) return -1.0
        return (ratingTwenty / 20.0).coerceIn(0.0, 1.0)
    }

    private fun mapStatus(raw: String): TrackStatus = when (raw) {
        "current" -> TrackStatus.READING
        "completed" -> TrackStatus.COMPLETED
        "on_hold" -> TrackStatus.ON_HOLD
        "dropped" -> TrackStatus.DROPPED
        "planned" -> TrackStatus.PLAN_TO_READ
        else -> TrackStatus.UNKNOWN
    }
}
