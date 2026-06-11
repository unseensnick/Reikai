package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import reikai.domain.recommendation.taste.TasteProfile
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
) {

    suspend fun load(
        manga: SManga,
        source: CatalogueSource,
        tracks: List<Track>,
        ranker: RecommendationRanker,
        taste: TasteProfile,
        onUpdate: suspend (List<RelatedMangaCandidate>) -> Unit,
    ): List<RelatedMangaCandidate> {
        val accumulator = Accumulator(manga, ranker, taste)

        coroutineScope {
            launch {
                source.getRelatedMangaList(
                    manga = manga,
                    exceptionHandler = { e ->
                        // One keyword search failing must not kill the carousel; the rest still populate it.
                        logcat(LogPriority.WARN, e) { "Related-mangas keyword search failed" }
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
            launch {
                fetcher.fetch(
                    title = manga.title,
                    tracks = tracks,
                    exceptionHandler = { /* already logged by the fetcher */ },
                    pushResults = { candidates -> accumulator.add(candidates)?.let { onUpdate(it) } },
                )
            }
        }

        return accumulator.snapshot()
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
                for (candidate in candidates) {
                    if (candidate.manga.url == selfUrl) continue
                    val keys = candidate.titleKeys()
                    if (keys.isEmpty()) continue
                    keys.forEach { agreementByKey.merge(it, 1, Int::plus) }
                    if (keys.any { it in seenTitleKeys }) continue
                    if (!accumulated.add(candidate)) continue // url already present
                    seenTitleKeys += keys
                    added = true
                }
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
