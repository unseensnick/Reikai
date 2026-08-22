package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import reikai.domain.recommendation.ReikaiRecommendationPreferences

/**
 * Pulls the user's full Kitsu manga library through GraphQL (`currentProfile.library.all`, paged 500
 * an entry) and normalizes each entry into a [TrackedEntry].
 *
 * Score is Kitsu's native 1..20 rating regardless of display preference, so it divides by 20 and
 * missing or 0 becomes -1.0. Statuses are Kitsu's own tokens, not the other trackers'.
 */
class KitsuLibraryFetcher(
    private val kitsu: Kitsu,
    private val preferences: ReikaiRecommendationPreferences,
) : TrackerLibraryFetcher {

    override val trackerId: Long = kitsu.id

    override fun isEnabled(): Boolean =
        preferences.pullLibraryFromKitsu.get() && kitsu.isLoggedIn

    override suspend fun fetchLibrary(): List<TrackedEntry> =
        kitsu.getUserLibrary().map { it.toTrackedEntry() }

    private fun KitsuLibraryEntry.toTrackedEntry(): TrackedEntry = TrackedEntry(
        trackerId = trackerId,
        remoteId = mangaId,
        title = title,
        score = normalizeTrackerScore(ratingTwenty, 20),
        status = mapStatus(status),
        tags = tags.map { it.toTagKey() }.filter { it.isNotEmpty() }.distinct(),
        malId = malId,
        anilistId = anilistId,
    )

    // Lower-cased because GraphQL reports the status enum in upper case where the JSON:API reported
    // it lower, and both spellings mean the same thing.
    private fun mapStatus(raw: String): TrackStatus = when (raw.lowercase()) {
        "current" -> TrackStatus.READING
        "completed" -> TrackStatus.COMPLETED
        "on_hold" -> TrackStatus.ON_HOLD
        "dropped" -> TrackStatus.DROPPED
        "planned" -> TrackStatus.PLAN_TO_READ
        else -> TrackStatus.UNKNOWN
    }
}
