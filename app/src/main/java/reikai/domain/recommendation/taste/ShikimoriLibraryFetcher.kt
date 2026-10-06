package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserRate
import eu.kanade.tachiyomi.data.track.shikimori.toShikimoriStatus
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import tachiyomi.core.common.preference.Preference

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
    preferences: ReikaiRecommendationPreferences,
) : TrackerLibraryFetcher {

    override val tracker: Tracker get() = shikimori

    override val pullPreference: Preference<Boolean> = preferences.pullLibraryFromShikimori

    override suspend fun fetchLibrary(): List<TrackedEntry> =
        shikimori.getUserLibrary().mapNotNull { it.toTrackedEntry() }

    private fun SMUserRate.toTrackedEntry(): TrackedEntry? {
        val manga = manga ?: return null
        val remoteId = manga.id.toLongOrNull() ?: return null
        return TrackedEntry(
            trackerId = trackerId,
            remoteId = remoteId,
            title = manga.name,
            score = normalizeTrackerScore(score, 10),
            status = shikimori.trackStatusOfRemote(status) { it.toShikimoriStatus() },
            tags = manga.genres.map { it.name }.toTagKeys(),
        )
    }
}
