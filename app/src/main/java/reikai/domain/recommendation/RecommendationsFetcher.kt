package reikai.domain.recommendation

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import kotlin.time.Duration.Companion.seconds

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
        skipTrackerIds: Set<Long> = emptySet(),
        exceptionHandler: (Throwable) -> Unit,
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
            runOne(provider, remoteId(trackerId), title, exceptionHandler, pushResults)
        }

        coroutineScope {
            enabledTrackerIds.forEach { trackerId -> launch { run(trackerId) } }
        }
    }

    private suspend fun runOne(
        provider: TrackerRecommendations,
        remoteId: Long?,
        title: String,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        runCatching { withTimeout(REQUEST_TIMEOUT) { provider.fetch(remoteId, title) } }
            .onSuccess { results -> if (results.isNotEmpty()) pushResults(results) }
            .onFailure { e ->
                when (e) {
                    // A slow tracker is dropped silently so it can't gate the carousel.
                    is TimeoutCancellationException -> Unit
                    // A real cancellation (screen closed) must propagate, not be logged as a failure,
                    // so structured concurrency unwinds cleanly.
                    is CancellationException -> throw e
                    else -> {
                        logcat(LogPriority.WARN, e) { "Tracker recommendations fetch failed (${provider.trackerName})" }
                        exceptionHandler(e)
                    }
                }
            }
    }

    companion object {
        /** Per-tracker hard cap: long enough for AniList GraphQL on a slow link, short enough that one
         *  hung tracker doesn't gate the carousel's load-complete signal for ~30s on the socket timeout. */
        private val REQUEST_TIMEOUT = 15.seconds
    }
}
