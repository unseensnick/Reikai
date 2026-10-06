package reikai.domain.recommendation

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tachiyomi.domain.track.model.Track

/**
 * Fans a single related-mangas request out across the tracker recommendation endpoints and pushes each
 * successful batch into the shared accumulator via [pushResults], alongside the source-native stream.
 * One tracker failing or timing out never blocks the others. Per tracker: if the user already tracks
 * this manga there, use that track's remote id; otherwise resolve it with a single title search. Kitsu
 * and Bangumi have no recommendations endpoint and register no provider.
 */
@Inject
class RecommendationsFetcher(
    private val trackerManager: TrackerManager,
    private val preferences: ReikaiRecommendationPreferences,
    private val providers: RecommendationProviders,
) {

    suspend fun fetch(
        title: String,
        tracks: List<Track>,
        skipTrackerIds: Set<Long>,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        val enabledTrackerIds = preferences.enabledRecommendationTrackerIds(trackerManager)
        if (enabledTrackerIds.isEmpty()) return

        fun remoteId(trackerId: Long): Long? =
            tracks.firstOrNull { it.trackerId == trackerId }?.remoteId

        suspend fun run(trackerId: Long) {
            // Skip trackers already handled by the loader's shared media-context fetch (where M is
            // tracked), so recs(M) isn't queried twice.
            if (trackerId in skipTrackerIds) return
            val provider = providers.forTracker(trackerId) ?: return
            runOne(provider, remoteId(trackerId), title, pushResults)
        }

        coroutineScope {
            enabledTrackerIds.forEach { trackerId -> launch { run(trackerId) } }
        }
    }

    private suspend fun runOne(
        provider: TrackerRecommendations,
        remoteId: Long?,
        title: String,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        cappedRecommendationCall({ "Tracker recommendations fetch failed (${provider.trackerName})" }) {
            provider.fetch(remoteId, title)
        }
            ?.takeIf { it.isNotEmpty() }
            ?.let { pushResults(it) }
    }
}
