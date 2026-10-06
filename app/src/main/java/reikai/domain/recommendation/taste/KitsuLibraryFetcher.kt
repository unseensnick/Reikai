package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import eu.kanade.tachiyomi.data.track.kitsu.toKitsuStatus
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import tachiyomi.core.common.preference.Preference

/**
 * Pulls the user's full Kitsu manga library through GraphQL (`currentProfile.library.all`, paged 500
 * an entry) and normalizes each entry into a [TrackedEntry].
 *
 * Score is Kitsu's native 1..20 rating regardless of display preference, so it divides by 20 and
 * missing or 0 becomes -1.0. Statuses are Kitsu's own tokens, not the other trackers'.
 */
class KitsuLibraryFetcher(
    private val kitsu: Kitsu,
    preferences: ReikaiRecommendationPreferences,
) : TrackerLibraryFetcher {

    override val tracker: Tracker get() = kitsu

    override val pullPreference: Preference<Boolean> = preferences.pullLibraryFromKitsu

    override suspend fun fetchLibrary(): List<TrackedEntry> =
        kitsu.getUserLibrary().map { it.toTrackedEntry() }

    private fun KitsuLibraryEntry.toTrackedEntry(): TrackedEntry = TrackedEntry(
        trackerId = trackerId,
        remoteId = mangaId,
        title = title,
        score = normalizeTrackerScore(ratingTwenty, 20),
        // Upper-cased because the JSON:API reported the status enum in lower case, GraphQL in upper.
        status = kitsu.trackStatusOfRemote(status.uppercase()) { it.toKitsuStatus().rawValue },
        tags = tags.toTagKeys(),
        malId = malId,
        anilistId = anilistId,
    )
}
