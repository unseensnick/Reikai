package reikai.domain.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Shared construction for the tracker recommendation providers, used by both the per-manga fan-out
 * ([RecommendationsFetcher]) and the taste-driven cross-recommendations
 * ([reikai.domain.recommendation.taste.TasteCandidateFetcher]).
 *
 * App-scoped because [client] must be built once per process: see its own note.
 */
@Inject
@SingleIn(AppScope::class)
class RecommendationProviders(
    private val networkHelper: NetworkHelper,
    private val trackerManager: TrackerManager,
    private val json: Json,
) {

    /**
     * Shared client for the public tracker recommendation endpoints, derived from the app's base
     * client with per-host rate limits so a burst of carousel opens can't hammer them. Built once
     * per process (via [lazy] on an app-scoped holder) so the limiter windows persist across fetches;
     * rebuilding per fetch would reset the windows and defeat the limit. Shikimori carries the
     * tightest caps for IP-ban headroom; Jikan needs both a per-second and a per-minute bucket.
     */
    val client: OkHttpClient by lazy {
        networkHelper.client.newBuilder()
            .rateLimitHost("https://graphql.anilist.co", permits = 85, period = 1.minutes)
            .rateLimitHost("https://api.jikan.moe", permits = 3, period = 1.seconds)
            .rateLimitHost("https://api.jikan.moe", permits = 58, period = 1.minutes)
            .rateLimitHost("https://api.mangaupdates.com", permits = 30, period = 1.minutes)
            .rateLimitHost("https://shikimori.io", permits = 2, period = 1.seconds)
            .rateLimitHost("https://shikimori.io", permits = 60, period = 1.minutes)
            .build()
    }

    /** The recs provider for a tracker id, or null if that tracker has no recommendations endpoint
     *  (Kitsu, Bangumi). */
    fun forTracker(trackerId: Long): TrackerRecommendations? = when (trackerId) {
        trackerManager.aniList.id -> AnilistRecommendations(client, trackerId, json)
        trackerManager.myAnimeList.id -> MyAnimeListRecommendations(client, trackerId, json)
        trackerManager.mangaUpdates.id -> MangaUpdatesRecommendations(client, trackerId, json)
        trackerManager.shikimori.id -> ShikimoriRecommendations(client, trackerId, json)
        else -> null
    }
}
