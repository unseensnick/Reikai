package eu.kanade.tachiyomi.data.recommendation

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.QuerySanitizer.sanitize
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.library.taste.interactor.ComputeTasteProfile
import yokai.domain.library.taste.interactor.GetTrackedEntries
import yokai.domain.library.taste.model.TrackStatus
import yokai.domain.library.taste.model.TrackedEntry

/**
 * Fans out taste-profile-driven candidate streams into the same `pushResults` callback used
 * by source-native and tracker recommendations. Candidates are source-origin
 * (`sourceId = catalogueSource.id`), so the existing dedup / 30-cap / source-vs-tracker
 * slot accounting in
 * [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter.fetchRelatedMangasFromSource] applies
 * uniformly.
 *
 * Two sub-mechanisms:
 * - **Tag search** (Phase 5.1): runs the user's top three taste-profile tags as searches
 *   against the current source. Gated by the [PreferencesHelper.injectTagSearchCandidates]
 *   user pref.
 * - **Cross-recommendation** (Phase 5.2): picks the top-rated tracked entries (score ≥ 0.8,
 *   COMPLETED or READING, max 5), searches the current source by title for each, and on a
 *   match calls the source's native related-mangas API. Gated by both the
 *   [PreferencesHelper.injectCrossRecommendationCandidates] user pref AND
 *   [CatalogueSource.supportsRelatedMangas] (an extension-side opt-in; true for every
 *   HTTP-backed extension now that the baseline lives in `HttpSource`).
 *
 * Silently produces zero results when the taste profile is empty, no favorite exists on
 * the current source, or either gate is off — the carousel falls back gracefully to
 * Phases 1–3 behavior.
 *
 * Sub-task errors route through [exceptionHandler]; one flow failing never blocks the other
 * or breaks the manga details page.
 */
class TasteCandidateFetcher(
    private val getTrackedEntries: GetTrackedEntries = Injekt.get(),
    private val computeTasteProfile: ComputeTasteProfile = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
) {
    suspend fun fetch(
        source: CatalogueSource,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (Pair<String, List<SManga>>, Boolean) -> Unit,
    ) {
        val tagSearchEnabled = preferences.injectTagSearchCandidates().get()
        val crossRecEnabled = preferences.injectCrossRecommendationCandidates().get() &&
            source.supportsRelatedMangas
        if (!tagSearchEnabled && !crossRecEnabled) return

        // Both flows depend on the same tracked-entries snapshot, so fetch it once. Layer A + B
        // composition + cross-tracker dedup happen inside GetTrackedEntries.
        val entries = getTrackedEntries.await()

        coroutineScope {
            if (tagSearchEnabled) launch { runTagSearch(source, entries, pushResults, exceptionHandler) }
            if (crossRecEnabled) launch { runCrossRecommendation(source, entries, pushResults, exceptionHandler) }
        }
    }

    private suspend fun runTagSearch(
        source: CatalogueSource,
        entries: List<TrackedEntry>,
        pushResults: suspend (Pair<String, List<SManga>>, Boolean) -> Unit,
        exceptionHandler: (Throwable) -> Unit,
    ) {
        val profile = computeTasteProfile(entries)
        val tags = profile.topTags(TASTE_TOP_TAG_COUNT)
        if (tags.isEmpty()) return

        coroutineScope {
            tags.forEach { tag ->
                launch {
                    runCatching {
                        withTimeout(REQUEST_TIMEOUT_MS) {
                            source.getSearchManga(1, tag.sanitize(), FilterList()).mangas
                        }
                    }
                        .onSuccess { mangas ->
                            if (mangas.isNotEmpty()) pushResults(tag to mangas, false)
                        }
                        .onFailure { e ->
                            if (e is TimeoutCancellationException) {
                                // Silent skip — slow source search shouldn't gate the carousel.
                            } else {
                                Logger.e(e) { "Tag-search candidate fetch failed for \"$tag\"" }
                                exceptionHandler(e)
                            }
                        }
                }
            }
        }
    }

    private suspend fun runCrossRecommendation(
        source: CatalogueSource,
        entries: List<TrackedEntry>,
        pushResults: suspend (Pair<String, List<SManga>>, Boolean) -> Unit,
        exceptionHandler: (Throwable) -> Unit,
    ) {
        val favorites = entries
            .filter {
                it.score >= FAVORITE_SCORE_THRESHOLD &&
                    (it.status == TrackStatus.COMPLETED || it.status == TrackStatus.READING)
            }
            .sortedByDescending { it.score }
            .take(MAX_FAVORITES)
        if (favorites.isEmpty()) return

        coroutineScope {
            favorites.forEach { favorite ->
                launch {
                    runCatching {
                        withTimeout(REQUEST_TIMEOUT_MS) {
                            val match = source
                                .getSearchManga(1, favorite.title.sanitize(), FilterList())
                                .mangas.firstOrNull() ?: return@withTimeout
                            val related = source.fetchRelatedMangaList(match)
                            if (related.isNotEmpty()) pushResults(favorite.title to related, false)
                        }
                    }.onFailure { e ->
                        if (e is TimeoutCancellationException) {
                            // Silent skip — slow source search or related fetch shouldn't gate the carousel.
                        } else {
                            Logger.e(e) { "Cross-recommendation candidate fetch failed for \"${favorite.title}\"" }
                            exceptionHandler(e)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Phase 5 default: take the user's three highest-scoring tags. */
        const val TASTE_TOP_TAG_COUNT = 3

        /** Normalized score (0..1) above which a tracked entry counts as a "favorite". */
        const val FAVORITE_SCORE_THRESHOLD = 0.8

        /** Hard cap on favorites consulted per page load — bounds the call count. */
        const val MAX_FAVORITES = 5

        /** Per-stream hard cap on each tag-search / cross-rec source call. Matches the per-task
         *  timeout in `RecommendationsFetcher` so a hung source request can't gate the carousel. */
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
