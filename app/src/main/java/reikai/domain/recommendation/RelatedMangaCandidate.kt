package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga

/**
 * Pool element for the related-mangas carousel: an [SManga] plus the source it counts as coming from,
 * or [RECOMMENDS_SOURCE] for a tracker recommendation whose URL belongs to no extension.
 * [altTitles] carries a tracker's synonyms so [titleKeys] dedups one series listed under
 * different titles; [trackerId] groups the carousel's tracker round-robin, and with [remoteId] lets
 * the hide filter match by id rather than title.
 * Equality is [SManga.url] alone, so a `LinkedHashSet` keeps the first-seen insertion.
 */
class RelatedMangaCandidate(
    val sourceId: Long,
    val manga: SManga,
    val altTitles: List<String> = emptyList(),
    val origin: RecommendationOrigin,
    val trackerId: Long? = null,
    val remoteId: Long? = null,
) {
    fun titleKeys(): Set<String> =
        (listOf(manga.title) + altTitles)
            .asSequence()
            .map(TitleNormalizer::normalize)
            .filter { it.isNotEmpty() }
            .toSet()

    fun withOrigin(newOrigin: RecommendationOrigin): RelatedMangaCandidate =
        RelatedMangaCandidate(sourceId, manga, altTitles, newOrigin, trackerId, remoteId)

    override fun equals(other: Any?): Boolean =
        other is RelatedMangaCandidate && manga.url == other.manga.url

    override fun hashCode(): Int = manga.url.hashCode()
}

/**
 * The local manga id a tapped [candidate] opens, stored first if it is new, or null for a tracker's
 * card, whose URL belongs to no installed source, so the caller routes it through global search.
 */
suspend fun NetworkToLocalManga.localIdOf(candidate: RelatedMangaCandidate): Long? {
    if (candidate.sourceId == RECOMMENDS_SOURCE) return null
    return this(candidate.manga.toDomainManga(candidate.sourceId)).id
}
