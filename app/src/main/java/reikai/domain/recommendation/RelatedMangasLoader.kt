package reikai.domain.recommendation

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import reikai.domain.recommendation.taste.TasteCandidateFetcher
import reikai.domain.recommendation.taste.TasteProfile
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

/**
 * Orchestrates the related-mangas carousel: fetches candidates from every stream, dedups them into
 * one pool, and ranks it, pushing intermediate snapshots so the UI renders progressively.
 *
 * Two streams run concurrently into a single shared [Accumulator]: the source-native path (the P1
 * `getRelatedMangaList` contract) and the tracker recommendation path ([RecommendationsFetcher],
 * one stream per enabled tracker). Sharing one accumulator means a title surfaced by both a source
 * and a tracker dedups to a single candidate and its cross-stream agreement counts across both.
 *
 * Dedup is two-keyed: by [SManga.url] (a source's own duplicate rows) and by normalized title set
 * (the same series listed under different titles across streams, via [TitleNormalizer] + each
 * candidate's [RelatedMangaCandidate.titleKeys]). Agreement (how many times a title key appeared
 * before dedup) is counted and fed to the ranker.
 */
class RelatedMangasLoader(
    private val fetcher: RecommendationsFetcher = Injekt.get(),
    private val tasteCandidateFetcher: TasteCandidateFetcher = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val preferences: ReikaiRecommendationPreferences = Injekt.get(),
) {

    suspend fun load(
        manga: SManga,
        source: CatalogueSource,
        tracks: List<Track>,
        ranker: RecommendationRanker,
        taste: TasteProfile,
        currentGenres: List<String>,
        onUpdate: suspend (List<RelatedMangaCandidate>) -> Unit,
    ): List<RelatedMangaCandidate> {
        val accumulator = Accumulator(manga, ranker, taste)

        coroutineScope {
            launch {
                source.getRelatedMangaList(
                    manga = manga,
                    exceptionHandler = { e ->
                        // One keyword search failing must not kill the carousel; the rest still populate
                        // it. A cancellation (screen closed) isn't a real failure, so don't log it.
                        if (e !is CancellationException) {
                            logcat(LogPriority.WARN, e) { "Related-mangas keyword search failed" }
                        }
                    },
                    pushResults = { (_, mangas), _ ->
                        val candidates = mangas.map { m ->
                            RelatedMangaCandidate(
                                sourceId = source.id,
                                trackerName = null,
                                manga = m,
                                origin = RecommendationOrigin.SourceNative,
                            )
                        }
                        accumulator.add(candidates)?.let { onUpdate(it) }
                    },
                )
            }
            // For each recs-capable tracker M is tracked on, fetch its media context (recs + genres)
            // once: the recs feed the carousel AND seed the taste-driven injection, so recs(M) isn't
            // queried twice.
            val handledTrackerIds = tracks
                .filter { RecommendationProviders.forTracker(it.trackerId, trackerManager) != null }
                .map { it.trackerId }
                .toSet()
            launch {
                // The master "Tracker recommendations" toggle gates every tracker-derived stream: the
                // direct recs below AND the taste injection. Off means a source-native-only carousel,
                // so skip the whole media-context fetch. Per-tracker sub-toggles still filter the
                // direct recs (by the map's tracker-id key) when the master is on.
                if (!preferences.includeTrackerRecommendations.get()) return@launch
                val contexts = fetchMediaContexts(tracks)
                val enabledTrackerIds = preferences.enabledRecommendationTrackerIds(trackerManager)
                contexts.forEach { (trackerId, ctx) ->
                    if (trackerId in enabledTrackerIds) accumulator.add(ctx.recommendations)?.let { onUpdate(it) }
                }
                tasteCandidateFetcher.fetch(
                    source = source,
                    mediaContexts = contexts,
                    sourceGenres = currentGenres,
                    exceptionHandler = { /* already logged by the fetcher */ },
                    pushResults = { candidates -> accumulator.add(candidates)?.let { onUpdate(it) } },
                )
            }
            launch {
                fetcher.fetch(
                    title = manga.title,
                    tracks = tracks,
                    skipTrackerIds = handledTrackerIds,
                    exceptionHandler = { /* already logged by the fetcher */ },
                    pushResults = { candidates -> accumulator.add(candidates)?.let { onUpdate(it) } },
                )
            }
        }

        return accumulator.snapshot()
    }

    /** Media context (recs + genres) for each recs-capable tracker M is tracked on, keyed by tracker
     *  id. A tracker that errors or times out is dropped (its injection just doesn't run). */
    private suspend fun fetchMediaContexts(tracks: List<Track>): Map<Long, TrackerRecommendations.MediaContext> =
        coroutineScope {
            tracks
                .mapNotNull { track ->
                    RecommendationProviders.forTracker(track.trackerId, trackerManager)?.let {
                        track to
                            it
                    }
                }
                .map { (track, provider) ->
                    async {
                        try {
                            track.trackerId to
                                withTimeout(MEDIA_CONTEXT_TIMEOUT) { provider.getMediaContext(track.remoteId) }
                        } catch (e: TimeoutCancellationException) {
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logcat(LogPriority.WARN, e) { "Media context fetch failed (${provider.trackerName})" }
                            null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }

    companion object {
        private val MEDIA_CONTEXT_TIMEOUT = 15.seconds
    }

    /**
     * Mutex-guarded dedup + agreement bookkeeping shared by both streams. [add] returns a fresh
     * ranked snapshot when it accepted at least one new candidate, else null (nothing changed).
     */
    private class Accumulator(
        manga: SManga,
        private val ranker: RecommendationRanker,
        private val taste: TasteProfile,
    ) {
        private val mutex = Mutex()
        private val accumulated = LinkedHashSet<RelatedMangaCandidate>()
        private val seenTitleKeys = HashSet<String>()
        private val agreementByKey = HashMap<String, Int>()
        private val selfUrl = manga.url

        init {
            // Never recommend the manga back to itself.
            TitleNormalizer.normalize(manga.title).takeIf { it.isNotEmpty() }?.let { seenTitleKeys += it }
        }

        suspend fun add(candidates: List<RelatedMangaCandidate>): List<RelatedMangaCandidate>? =
            mutex.withLock {
                var added = false
                // Count each title key at most once per push, not once per candidate: a stream that
                // lists the same series twice in one batch (e.g. MangaUpdates' recommendations +
                // categoryRecommendations) must not manufacture cross-source agreement the ranker rewards.
                val batchKeys = HashSet<String>()
                for (candidate in candidates) {
                    if (candidate.manga.url == selfUrl) continue
                    val keys = candidate.titleKeys()
                    if (keys.isEmpty()) continue
                    batchKeys += keys
                    if (keys.any { it in seenTitleKeys }) continue
                    if (!accumulated.add(candidate)) continue // url already present
                    seenTitleKeys += keys
                    added = true
                }
                batchKeys.forEach { agreementByKey.merge(it, 1, Int::plus) }
                if (added) rankSnapshot() else null
            }

        suspend fun snapshot(): List<RelatedMangaCandidate> = mutex.withLock { rankSnapshot() }

        private fun rankSnapshot(): List<RelatedMangaCandidate> {
            val agreementByUrl = accumulated.associate { c ->
                c.manga.url to (c.titleKeys().maxOfOrNull { agreementByKey[it] ?: 1 } ?: 1)
            }
            return ranker.rank(accumulated.toList(), taste, agreementByUrl)
        }
    }
}
