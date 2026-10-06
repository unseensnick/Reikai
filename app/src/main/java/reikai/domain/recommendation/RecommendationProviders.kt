package reikai.domain.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesApi
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
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
 */
@Inject
@SingleIn(AppScope::class)
class RecommendationProviders(
    private val networkHelper: NetworkHelper,
    private val trackerManager: TrackerManager,
    private val json: Json,
) {

    /**
     * Shared client for the public tracker recommendation endpoints, with per-host rate limits so a
     * burst of carousel opens can't hammer them. This holder is app-scoped and the client [lazy] so
     * the limiter windows persist across fetches; rebuilding per fetch would reset them and defeat
     * the limit. Shikimori carries the tightest caps for IP-ban headroom; Jikan needs both a
     * per-second and a per-minute bucket.
     */
    val client: OkHttpClient by lazy {
        networkHelper.client.newBuilder()
            .rateLimitHost(AnilistApi.API_URL, permits = 85, period = 1.minutes)
            .rateLimitHost(MyAnimeListRecommendations.JIKAN_URL, permits = 3, period = 1.seconds)
            .rateLimitHost(MyAnimeListRecommendations.JIKAN_URL, permits = 58, period = 1.minutes)
            .rateLimitHost(MangaUpdatesApi.BASE_URL, permits = 30, period = 1.minutes)
            .rateLimitHost(ShikimoriApi.BASE_URL, permits = 2, period = 1.seconds)
            .rateLimitHost(ShikimoriApi.BASE_URL, permits = 60, period = 1.minutes)
            .build()
    }

    /** The recs provider for a tracker id, or null if that tracker has no recommendations endpoint
     *  (Kitsu, Bangumi). */
    fun forTracker(trackerId: Long): TrackerRecommendations? = when (val tracker = trackerManager.get(trackerId)) {
        is Anilist -> AnilistRecommendations(client, tracker, json)
        is MyAnimeList -> MyAnimeListRecommendations(client, tracker, json)
        is MangaUpdates -> MangaUpdatesRecommendations(client, tracker, json)
        is Shikimori -> ShikimoriRecommendations(client, tracker, json)
        else -> null
    }
}
