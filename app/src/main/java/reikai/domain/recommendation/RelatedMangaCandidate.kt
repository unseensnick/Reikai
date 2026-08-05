package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Pool element for the related-mangas carousel, pairing an [SManga] with the source it should be
 * treated as coming from: an installed source, or [RECOMMENDS_SOURCE] for a tracker recommendation
 * whose URL belongs to no extension. [trackerName] is set only for tracker-origin entries and lets
 * the merge step round-robin slots fairly. [altTitles] carries a tracker's synonyms so [titleKeys]
 * can dedup one series listed under different titles. [trackerId] plus [remoteId] are the stable
 * tracker identity, letting the hide filter match by id rather than title. Equality is by
 * [SManga.url] alone, so a `LinkedHashSet` keeps the first-seen insertion.
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
    /** Every normalized title that identifies this candidate (primary + alternatives), deduped. */
    fun titleKeys(): Set<String> =
        (listOf(manga.title) + altTitles)
            .asSequence()
            .map(TitleNormalizer::normalize)
            .filter { it.isNotEmpty() }
            .toSet()

    /** A copy with a different [origin], preserving identity (used to re-tag tracker recs as cross-recs). */
    fun withOrigin(newOrigin: RecommendationOrigin): RelatedMangaCandidate =
        RelatedMangaCandidate(sourceId, trackerName, manga, altTitles, newOrigin, trackerId, remoteId)

    override fun equals(other: Any?): Boolean =
        other is RelatedMangaCandidate && manga.url == other.manga.url

    override fun hashCode(): Int = manga.url.hashCode()
}
