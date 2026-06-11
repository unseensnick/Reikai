package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import reikai.domain.recommendation.taste.TasteProfile
import tachiyomi.core.common.util.system.logcat

/**
 * Orchestrates the related-mangas carousel: fetches candidates, dedups them, and ranks the pool,
 * streaming intermediate results so the UI can render progressively.
 *
 * Dedup is two-keyed: by [SManga.url] (a source's own duplicate rows) and by normalized title set
 * (the same series listed under different titles across sources, the old "dupes slip through" bug,
 * now fixed via [TitleNormalizer] + each candidate's [RelatedMangaCandidate.titleKeys]). Cross-stream
 * agreement (how many times a title key appeared before dedup) is counted and fed to the ranker.
 *
 * This first cut covers the source-native path (the P1 `getRelatedMangaList` contract); tracker
 * recommendation providers are added as additional streams in a later step. [pushResults] is called
 * from parallel keyword searches inside the source, so accumulation is guarded by a [Mutex].
 */
class RelatedMangasLoader {

    suspend fun loadFromSource(
        manga: SManga,
        source: CatalogueSource,
        ranker: RecommendationRanker,
        taste: TasteProfile,
        onUpdate: suspend (List<RelatedMangaCandidate>) -> Unit,
    ): List<RelatedMangaCandidate> {
        val mutex = Mutex()
        val accumulated = LinkedHashSet<RelatedMangaCandidate>()
        val seenTitleKeys = HashSet<String>()
        val agreementByKey = HashMap<String, Int>()

        // Never recommend the manga back to itself.
        TitleNormalizer.normalize(manga.title).takeIf { it.isNotEmpty() }?.let { seenTitleKeys += it }
        val selfUrl = manga.url

        fun rankSnapshot(): List<RelatedMangaCandidate> {
            val agreementByUrl = accumulated.associate { c ->
                c.manga.url to (c.titleKeys().maxOfOrNull { agreementByKey[it] ?: 1 } ?: 1)
            }
            return ranker.rank(accumulated.toList(), taste, agreementByUrl)
        }

        source.getRelatedMangaList(
            manga = manga,
            exceptionHandler = { e ->
                // One keyword search failing must not kill the carousel; the rest still populate it.
                logcat(LogPriority.WARN, e) { "Related-mangas keyword search failed" }
            },
            pushResults = { (_, mangas), _ ->
                val snapshot = mutex.withLock {
                    var added = false
                    for (m in mangas) {
                        if (m.url == selfUrl) continue
                        val candidate = RelatedMangaCandidate(
                            sourceId = source.id,
                            trackerName = null,
                            manga = m,
                            origin = RecommendationOrigin.SourceNative,
                        )
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
                if (snapshot != null) onUpdate(snapshot)
            },
        )

        return mutex.withLock { rankSnapshot() }
    }
}
