package eu.kanade.tachiyomi.data.track.anilist

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.track.anilist.dto.ALLibraryEntry
import eu.kanade.tachiyomi.data.track.library.TrackerLibraryFetcher
import yokai.domain.library.taste.model.TrackStatus
import yokai.domain.library.taste.model.TrackedEntry

/**
 * Pulls the user's full AniList manga library via one GraphQL `MediaListCollection` call and
 * normalizes each entry into a [TrackedEntry] for the taste profile cache.
 *
 * Score: `POINT_100` requested directly from the GraphQL layer, so we always get a 0..100
 * integer regardless of the user's display format preference — divided by 100 and clamped
 * to 0..1. Raw 0 (unrated) becomes -1.0 so the compute formula can distinguish "rated low"
 * from "no rating".
 *
 * Tags: union of `genres` (broad) and `tags[].name` (specific) — both feed the taste profile
 * with equal weight in v1. AniList's tag `rank` is ignored for now; revisit if mismatches
 * with other trackers' taxonomies prove actionable.
 */
class AnilistLibraryFetcher(
    private val anilist: Anilist,
) : TrackerLibraryFetcher {

    override val tracker: Anilist = anilist

    override suspend fun fetchLibrary(): List<TrackedEntry> {
        val userId = anilist.getUsername().toIntOrNull()
        if (userId == null) {
            Logger.d { "AnilistLibraryFetcher: empty/invalid username, skipping" }
            return emptyList()
        }
        val entries = anilist.getUserLibrary(userId)
        val now = System.currentTimeMillis()
        val mapped = entries.map { it.toTrackedEntry(now) }
        // Status breakdown verifies that ON_HOLD / DROPPED / PLAN_TO_READ map correctly instead
        // of all falling through to UNKNOWN — the one thing we can't verify from total count alone.
        val statusCounts = mapped.groupingBy { it.status }.eachCount()
        Logger.d {
            "AnilistLibraryFetcher: fetched ${mapped.size} entries, status=$statusCounts"
        }
        return mapped
    }

    private fun ALLibraryEntry.toTrackedEntry(fetchedAt: Long): TrackedEntry {
        return TrackedEntry(
            trackerId = anilist.id,
            remoteId = media.id,
            title = media.title.userPreferred,
            score = normalizeScore(scoreRaw),
            status = mapStatus(status),
            tags = collectTags(),
            fetchedAt = fetchedAt,
            // Cross-tracker dedup keys: malId via AniList's Media.idMal (often null for
            // manhwa/manhua); anilistId trivially as our own remote id so Kitsu's anilist
            // mappings can join us on the second key when the MAL cross-ref is missing.
            malId = media.idMal,
            anilistId = media.id,
        )
    }

    private fun ALLibraryEntry.collectTags(): List<String> {
        return (media.genres + media.tags.map { it.name })
            .map { it.replace(",", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun normalizeScore(scoreRaw: Int): Double {
        if (scoreRaw <= 0) return -1.0
        return (scoreRaw / 100.0).coerceIn(0.0, 1.0)
    }

    private fun mapStatus(raw: String): TrackStatus = when (raw) {
        "CURRENT", "REPEATING" -> TrackStatus.READING
        "COMPLETED" -> TrackStatus.COMPLETED
        "PAUSED" -> TrackStatus.ON_HOLD
        "DROPPED" -> TrackStatus.DROPPED
        "PLANNING" -> TrackStatus.PLAN_TO_READ
        else -> TrackStatus.UNKNOWN
    }
}
