package reikai.domain.recommendation.taste

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.QuerySanitizer.sanitize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import reikai.domain.recommendation.RecommendationOrigin
import reikai.domain.recommendation.RecommendationProviders
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.domain.recommendation.TrackerRecommendations
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.seconds

/**
 * Taste-driven extra candidates, pushed into the shared accumulator alongside the source-native and
 * tracker-recs streams. Gated on the current manga being tracked on a recs-capable tracker, whose
 * [TrackerRecommendations.MediaContext] the loader fetches once and shares. Two sub-flows, each
 * behind its own preference and neither blocking the other: cross-rec seeds from highly-rated
 * tracked titles in this manga's own recommendation list, so the tracker graph decides similarity
 * rather than genre heuristics; and tag search, which queries the source for genres the user likes.
 */
@Inject
class TasteCandidateFetcher(
    private val repository: TasteLibraryRepository,
    private val computeTasteProfile: ComputeTasteProfile,
    private val preferences: ReikaiRecommendationPreferences,
    private val providers: RecommendationProviders,
) {

    suspend fun fetch(
        source: CatalogueSource,
        mediaContexts: Map<Long, TrackerRecommendations.MediaContext>,
        sourceGenres: List<String>,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        val tagSearchEnabled = preferences.injectTagSearchCandidates.get()
        val crossRecEnabled = preferences.injectCrossRecommendationCandidates.get()
        if (!tagSearchEnabled && !crossRecEnabled) return
        // Gate: M is tracked on a recs-capable tracker (the loader passes its context).
        if (mediaContexts.isEmpty()) return

        val entries = repository.getAll()
        val profile = computeTasteProfile(entries)

        coroutineScope {
            if (tagSearchEnabled) {
                val genres = mediaContexts.values.flatMap { it.genres }.distinct().ifEmpty { sourceGenres }
                launch { runTagSearch(source, profile, genres, exceptionHandler, pushResults) }
            }
            if (crossRecEnabled && entries.isNotEmpty()) {
                mediaContexts.forEach { (trackerId, context) ->
                    val provider = providers.forTracker(trackerId) ?: return@forEach
                    val recsIds = context.recommendations.mapNotNullTo(HashSet()) { it.remoteId }
                    val seeds = selectCrossRecSeeds(entries, recsIds, trackerId, MAX_FAVORITES)
                    launch { runCrossRecommendation(provider, seeds, exceptionHandler, pushResults) }
                }
            }
        }
    }

    private suspend fun runTagSearch(
        source: CatalogueSource,
        profile: TasteProfile,
        genres: List<String>,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        val tags = selectContextualTags(profile, genres, TASTE_TOP_TAG_COUNT)
        if (tags.isEmpty()) return

        coroutineScope {
            tags.forEach { tag ->
                launch {
                    runCatching {
                        withTimeout(REQUEST_TIMEOUT) {
                            source.getSearchManga(1, tag.sanitize(), FilterList()).mangas
                        }
                    }
                        .onSuccess { mangas ->
                            if (mangas.isNotEmpty()) {
                                pushResults(mangas.map { candidate(source, it, RecommendationOrigin.TagSearch(tag)) })
                            }
                        }
                        .onFailure {
                            handleFailure(it, exceptionHandler) { "Tag-search candidate fetch failed for \"$tag\"" }
                        }
                }
            }
        }
    }

    private suspend fun runCrossRecommendation(
        provider: TrackerRecommendations,
        seeds: List<TrackedEntry>,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        coroutineScope {
            seeds.forEach { seed ->
                launch {
                    runCatching { withTimeout(REQUEST_TIMEOUT) { provider.getRecsById(seed.remoteId) } }
                        .onSuccess { recs ->
                            if (recs.isNotEmpty()) {
                                pushResults(recs.map { it.withOrigin(RecommendationOrigin.CrossRec(seed.title)) })
                            }
                        }
                        .onFailure {
                            handleFailure(it, exceptionHandler) {
                                "Cross-recommendation candidate fetch failed for \"${seed.title}\""
                            }
                        }
                }
            }
        }
    }

    private fun candidate(source: CatalogueSource, manga: SManga, origin: RecommendationOrigin) =
        RelatedMangaCandidate(sourceId = source.id, trackerName = null, manga = manga, origin = origin)

    private fun handleFailure(e: Throwable, exceptionHandler: (Throwable) -> Unit, message: () -> String) {
        when (e) {
            // A slow source search shouldn't gate the carousel.
            is TimeoutCancellationException -> Unit
            // A real cancellation (screen closed) must propagate so structured concurrency unwinds.
            is CancellationException -> throw e
            else -> {
                logcat(LogPriority.WARN, e) { message() }
                exceptionHandler(e)
            }
        }
    }

    companion object {
        const val TASTE_TOP_TAG_COUNT = 3

        /** Normalized score (0..1) above which a tracked entry counts as a "favorite". */
        const val FAVORITE_SCORE_THRESHOLD = 0.8

        /** Hard cap on favorites consulted per load, bounding the source call count. */
        const val MAX_FAVORITES = 5

        /** Per-call hard cap; matches [reikai.domain.recommendation.RecommendationsFetcher] so a hung
         *  source request can't gate the carousel's load-complete signal. */
        private val REQUEST_TIMEOUT = 15.seconds
    }
}

/**
 * The current manga's own genres that the user scores positively, highest affinity first, capped at
 * [n]. Empty when the manga has no genres in the profile (so tag search stays relevant rather than
 * falling back to a global, manga-agnostic set).
 */
internal fun selectContextualTags(profile: TasteProfile, currentGenres: List<String>, n: Int): List<String> {
    if (currentGenres.isEmpty() || profile.tagScores.isEmpty()) return emptyList()
    return currentGenres.asSequence()
        .map { it.toTagKey() }
        .filter { it.isNotEmpty() }
        .distinct()
        .mapNotNull { genre -> profile.tagScores[genre]?.let { score -> genre to score } }
        .filter { it.second > 0.0 }
        .sortedByDescending { it.second }
        .take(n)
        .map { it.first }
        .toList()
}

/**
 * Titles the user rated highly that the tracker itself lists among the current manga's own
 * recommendations ([recsIds]), so similarity comes from the tracker graph rather than any genre
 * heuristic. Highest score first, capped at [max].
 */
internal fun selectCrossRecSeeds(
    entries: List<TrackedEntry>,
    recsIds: Set<Long>,
    trackerId: Long,
    max: Int,
): List<TrackedEntry> =
    entries.asSequence()
        .filter {
            it.trackerId == trackerId &&
                it.remoteId in recsIds &&
                it.score >= TasteCandidateFetcher.FAVORITE_SCORE_THRESHOLD &&
                (it.status == TrackStatus.COMPLETED || it.status == TrackStatus.READING)
        }
        .sortedByDescending { it.score }
        .take(max)
        .toList()
