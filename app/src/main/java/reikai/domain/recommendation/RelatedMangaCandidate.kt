package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Pool element for the related-mangas carousel. Pairs an [SManga] with the source id it should be
 * treated as coming from when clicked: either an installed source for source-native / keyword
 * suggestions, or [RECOMMENDS_SOURCE] for tracker recommendations whose URL doesn't belong to any
 * installed extension.
 *
 * [trackerName] is set only for tracker-origin entries and lets the merge step round-robin tracker
 * slots fairly across trackers. [altTitles] carries the alternative titles / synonyms a tracker
 * reported (AniList romaji/english/native + synonyms, Jikan title_synonyms, MangaUpdates associated
 * names), so [titleKeys] can dedup the same series listed under different titles across sources.
 * [origin] is the provenance for the recommendations browse-screen grouping.
 *
 * [trackerId] + [remoteId] are the candidate's stable tracker identity when it came from a tracker
 * recs endpoint (null for source-native / tag-search candidates, which carry only a title). They let
 * the hide filter match the user's tracker lists by id rather than by title.
 *
 * Equality is by [SManga.url] only, so a `LinkedHashSet` keeps the first-seen insertion (normally
 * source-native, which usually completes first).
 */
class RelatedMangaCandidate(
    val sourceId: Long,
    val trackerName: String?,
    val manga: SManga,
    val altTitles: List<String> = emptyList(),
    val origin: RecommendationOrigin = RecommendationOrigin.SourceNative,
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
