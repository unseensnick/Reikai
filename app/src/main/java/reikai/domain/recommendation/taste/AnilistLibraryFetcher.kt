package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.dto.ALLibraryEntry
import reikai.domain.recommendation.ReikaiRecommendationPreferences

/**
 * Pulls the user's full AniList manga library in one GraphQL call and normalizes each entry into a
 * [TrackedEntry] for the taste-profile cache.
 *
 * Score: AniList is asked for `POINT_100` directly, so it is always a 0..100 integer regardless of
 * the user's display format; divided by 100 and clamped. Raw 0 (unrated) becomes -1.0 so the
 * compute formula can tell "rated low" from "no rating". Tags: union of broad `genres` and specific
 * `tags[].name`, both weighted equally in v1.
 */
class AnilistLibraryFetcher(
    private val anilist: Anilist,
    private val preferences: ReikaiRecommendationPreferences,
) : TrackerLibraryFetcher {

    override val trackerId: Long = anilist.id

    override fun isEnabled(): Boolean =
        preferences.pullLibraryFromAnilist.get() && anilist.isLoggedIn

    override suspend fun fetchLibrary(): List<TrackedEntry> =
        anilist.getUserLibrary().map { it.toTrackedEntry() }

    private fun ALLibraryEntry.toTrackedEntry(): TrackedEntry = TrackedEntry(
        trackerId = trackerId,
        remoteId = media.id,
        title = media.title.userPreferred,
        score = normalizeTrackerScore(scoreRaw, 100),
        status = mapStatus(status),
        tags = collectTags(),
        // Cross-tracker dedup keys: malId via AniList's Media.idMal (often null for manhwa/manhua);
        // anilistId is our own remote id so a Kitsu entry's anilist mapping can join on the 2nd key.
        malId = media.idMal,
        anilistId = media.id,
    )

    private fun ALLibraryEntry.collectTags(): List<String> =
        (media.genres + media.tags.map { it.name })
            .map { it.toTagKey() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun mapStatus(raw: String?): TrackStatus = when (raw) {
        "CURRENT", "REPEATING" -> TrackStatus.READING
        "COMPLETED" -> TrackStatus.COMPLETED
        "PAUSED" -> TrackStatus.ON_HOLD
        "DROPPED" -> TrackStatus.DROPPED
        "PLANNING" -> TrackStatus.PLAN_TO_READ
        else -> TrackStatus.UNKNOWN
    }
}
