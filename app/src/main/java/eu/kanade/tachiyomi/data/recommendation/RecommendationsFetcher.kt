package eu.kanade.tachiyomi.data.recommendation

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.SManga
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.track.interactor.GetTrack

/**
 * Fans out a single related-mangas request across the three tracker recommendation endpoints
 * (AniList, MyAnimeList via Jikan, MangaUpdates) and pushes successful batches into the same
 * `pushResults` callback used by source-native and keyword-search results. Existing dedup and
 * 30-item cap in [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter.fetchRelatedMangasFromSource]
 * apply uniformly.
 *
 * Sub-task errors route through [exceptionHandler]; one tracker failing never blocks the others.
 *
 * Per-tracker dispatch: if the user has an existing track entry for [manga], use that tracker's
 * `media_id` directly; otherwise fall back to title-search to resolve the id (matches Komikku's
 * `TrackerRecommendationPagingSource.requestNextPage` shape).
 */
class RecommendationsFetcher(
    private val getTrack: GetTrack = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
) {
    suspend fun fetch(
        manga: Manga,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (Pair<String, List<SManga>>, Boolean) -> Unit,
    ) {
        if (!preferences.includeTrackerRecommendations().get()) return

        val mangaId = manga.id
        val tracks = getTrack.awaitAllByMangaId(mangaId)
        val anilistId = tracks.firstOrNull { it.sync_id == TrackManager.ANILIST }?.media_id
        val malId = tracks.firstOrNull { it.sync_id == TrackManager.MYANIMELIST }?.media_id
        val muId = tracks.firstOrNull { it.sync_id == TrackManager.MANGA_UPDATES }?.media_id

        val title = manga.title
        val client = recsClient

        coroutineScope {
            if (preferences.aniListRecommendations().get()) {
                launch { runOne(AnilistRecommendations(client), anilistId, title, exceptionHandler, pushResults) }
            }
            if (preferences.myAnimeListRecommendations().get()) {
                launch { runOne(MyAnimeListRecommendations(client), malId, title, exceptionHandler, pushResults) }
            }
            if (preferences.mangaUpdatesRecommendations().get()) {
                launch { runOne(MangaUpdatesRecommendations(client), muId, title, exceptionHandler, pushResults) }
            }
        }
    }

    private suspend fun runOne(
        tracker: TrackerRecommendations,
        remoteId: Long?,
        title: String,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (Pair<String, List<SManga>>, Boolean) -> Unit,
    ) {
        runCatching { withTimeout(REQUEST_TIMEOUT_MS) { tracker.fetch(remoteId, title) } }
            .onSuccess { results ->
                if (results.isNotEmpty()) pushResults(tracker.trackerName to results, false)
            }
            .onFailure { e ->
                if (e is TimeoutCancellationException) {
                    // Slow tracker → silently skip so it doesn't gate the carousel's
                    // load-complete signal for the other trackers and source-native results.
                } else {
                    Logger.e(e) { "Tracker recommendations fetch failed (${tracker.trackerName})" }
                    exceptionHandler(e)
                }
            }
    }

    companion object {
        /** Per-tracker hard cap on the recommendation fetch. Long enough that AniList GraphQL
         *  on a slow connection completes; short enough that one hung tracker doesn't gate the
         *  carousel's `relatedMangasLoading = false` toggle for ~30s on OkHttp's socket timeout. */
        private const val REQUEST_TIMEOUT_MS = 15_000L

        /**
         * Shared client for the public tracker recommendation endpoints, derived from the app's
         * base client with per-host rate limits so a burst of carousel opens can't hammer them.
         * Jikan is the strictest (3 req/sec and ~60/min), so it carries both a per-second and a
         * per-minute cap. Built once per process so the limiter windows persist across fetches;
         * building it per fetch would reset the windows and defeat the limit.
         */
        private val recsClient by lazy {
            Injekt.get<NetworkHelper>().client.newBuilder()
                .rateLimitHost("https://graphql.anilist.co".toHttpUrl(), permits = 85, period = 1, unit = TimeUnit.MINUTES)
                .rateLimitHost("https://api.jikan.moe".toHttpUrl(), permits = 3, period = 1, unit = TimeUnit.SECONDS)
                .rateLimitHost("https://api.jikan.moe".toHttpUrl(), permits = 58, period = 1, unit = TimeUnit.MINUTES)
                .rateLimitHost("https://api.mangaupdates.com".toHttpUrl(), permits = 30, period = 1, unit = TimeUnit.MINUTES)
                .build()
        }
    }
}
