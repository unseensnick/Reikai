package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import reikai.domain.recommendation.ReikaiRecommendationPreferences

/**
 * Pulls the user's full Kitsu manga library via the JSON:API `/library-entries` endpoint (categories
 * + mappings side-loaded, paged 500/entry) and normalizes each entry into a [TrackedEntry].
 *
 * Score: Kitsu's `ratingTwenty` (1..20 regardless of display preference), divided by 20; missing/0
 * becomes -1.0. Status: Kitsu uses `current` / `planned` rather than the other trackers' tokens.
 * Tags: the manga's `categories` titles. Cross-tracker [malId] / [anilistId] come from mappings.
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

    private fun mapStatus(raw: String): TrackStatus = when (raw) {
        "current" -> TrackStatus.READING
        "completed" -> TrackStatus.COMPLETED
        "on_hold" -> TrackStatus.ON_HOLD
        "dropped" -> TrackStatus.DROPPED
        "planned" -> TrackStatus.PLAN_TO_READ
        else -> TrackStatus.UNKNOWN
    }
}
