package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserRate
import reikai.domain.recommendation.ReikaiRecommendationPreferences

/**
 * Pulls the user's full Shikimori manga library via the GraphQL `userRates` query (genres inline,
 * 50/page) and normalizes each entry into a [TrackedEntry].
 *
 * Score: Shikimori's 0..10 integer scale, divided by 10; 0 (unrated) becomes -1.0. Status: Shikimori
 * uses anime-centric tokens, so `watching` / `rewatching` map to READING for manga. Entries with a
 * missing or non-numeric manga id are dropped.
 */
class ShikimoriLibraryFetcher(
    private val shikimori: Shikimori,
    private val preferences: ReikaiRecommendationPreferences,
) : TrackerLibraryFetcher {

    override val trackerId: Long = shikimori.id

    override fun isEnabled(): Boolean =
        preferences.pullLibraryFromShikimori.get() && shikimori.isLoggedIn

    override suspend fun fetchLibrary(): List<TrackedEntry> =
        shikimori.getUserLibrary().mapNotNull { it.toTrackedEntry() }

    private fun SMUserRate.toTrackedEntry(): TrackedEntry? {
        val manga = manga ?: return null
        val remoteId = manga.id.toLongOrNull() ?: return null
        return TrackedEntry(
            trackerId = trackerId,
            remoteId = remoteId,
            title = manga.name,
            score = normalizeScore(score),
            status = mapStatus(status),
            tags = manga.genres.map { it.name.lowercase().trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    private fun normalizeScore(score: Int): Double =
        if (score <= 0) -1.0 else (score / 10.0).coerceIn(0.0, 1.0)

    private fun mapStatus(raw: String?): TrackStatus = when (raw) {
        "watching", "rewatching" -> TrackStatus.READING
        "completed" -> TrackStatus.COMPLETED
        "on_hold" -> TrackStatus.ON_HOLD
        "dropped" -> TrackStatus.DROPPED
        "planned" -> TrackStatus.PLAN_TO_READ
        else -> TrackStatus.UNKNOWN
    }
}
