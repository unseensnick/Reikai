package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Pool element for the related-mangas carousel: an [SManga] plus the source it counts as coming from,
 * or [RECOMMENDS_SOURCE] for a tracker recommendation whose URL belongs to no extension.
 * [trackerName] is set only for tracker-origin entries, letting the merge step round-robin slots
 * fairly; [altTitles] carries a tracker's synonyms so [titleKeys] dedups one series listed under
 * different titles; [trackerId] plus [remoteId] let the hide filter match by id rather than title.
 * Equality is [SManga.url] alone, so a `LinkedHashSet` keeps the first-seen insertion.
 */
class RelatedMangaCandidate(
    val sourceId: Long,
    val trackerName: String?,
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
        RelatedMangaCandidate(sourceId, trackerName, manga, altTitles, newOrigin, trackerId, remoteId)

    override fun equals(other: Any?): Boolean =
        other is RelatedMangaCandidate && manga.url == other.manga.url

    override fun hashCode(): Int = manga.url.hashCode()
}
