package reikai.domain.recommendation

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Fans a single related-mangas request out across the tracker recommendation endpoints (AniList,
 * MyAnimeList via Jikan, MangaUpdates, Shikimori) and pushes each successful batch into the shared
 * accumulator via [pushResults], alongside the source-native stream. One tracker failing or timing
 * out never blocks the others.
 *
 * Dispatch per tracker: if the user already tracks this manga on it, use that track's remote id
 * directly; otherwise resolve the id via a single title search. Kitsu and Bangumi have no
 * recommendations endpoint and register no provider (their contribution is taste-profile-only, R4).
 */
class RecommendationsFetcher(
    private val trackerManager: TrackerManager = Injekt.get(),
    private val preferences: ReikaiRecommendationPreferences = Injekt.get(),
) {

    suspend fun fetch(
        title: String,
        tracks: List<Track>,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        if (!preferences.includeTrackerRecommendations.get()) return

        val client = recsClient

        fun remoteId(trackerId: Long): Long? =
            tracks.firstOrNull { it.trackerId == trackerId }?.remoteId

        coroutineScope {
            if (preferences.anilistRecommendations.get()) {
                launch {
                    runOne(AnilistRecommendations(client), remoteId(trackerManager.aniList.id), title, exceptionHandler, pushResults)
                }
            }
            if (preferences.myAnimeListRecommendations.get()) {
                launch {
                    runOne(MyAnimeListRecommendations(client), remoteId(trackerManager.myAnimeList.id), title, exceptionHandler, pushResults)
                }
            }
            if (preferences.mangaUpdatesRecommendations.get()) {
                launch {
                    runOne(MangaUpdatesRecommendations(client), remoteId(trackerManager.mangaUpdates.id), title, exceptionHandler, pushResults)
                }
            }
            if (preferences.shikimoriRecommendations.get()) {
                launch {
                    runOne(ShikimoriRecommendations(client), remoteId(trackerManager.shikimori.id), title, exceptionHandler, pushResults)
                }
            }
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
                    // A real cancellation (screen closed) must propagate, not be swallowed/logged as a
                    // failure, so structured concurrency unwinds cleanly.
                    is CancellationException -> throw e
                    // One tracker erroring never blocks the others; log and surface it.
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

        /**
         * Shared client for the public tracker recommendation endpoints, derived from the app's base
         * client with per-host rate limits so a burst of carousel opens can't hammer them. Built once
         * per process (via [lazy]) so the limiter windows persist across fetches; rebuilding per fetch
         * would reset the windows and defeat the limit. Shikimori carries the tightest caps for IP-ban
         * headroom; Jikan needs both a per-second and a per-minute bucket.
         */
        private val recsClient: OkHttpClient by lazy {
            Injekt.get<NetworkHelper>().client.newBuilder()
                .rateLimitHost("https://graphql.anilist.co", permits = 85, period = 1.minutes)
                .rateLimitHost("https://api.jikan.moe", permits = 3, period = 1.seconds)
                .rateLimitHost("https://api.jikan.moe", permits = 58, period = 1.minutes)
                .rateLimitHost("https://api.mangaupdates.com", permits = 30, period = 1.minutes)
                .rateLimitHost("https://shikimori.one", permits = 2, period = 1.seconds)
                .rateLimitHost("https://shikimori.one", permits = 60, period = 1.minutes)
                .build()
        }
    }
}
