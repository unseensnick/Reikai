package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Pool element for the related-mangas carousel. Pairs an [SManga] with the source id it should be
 * treated as coming from when clicked: either an installed source for source-native / keyword
 * suggestions, or [RECOMMENDS_SOURCE] for tracker recommendations whose URL doesn't belong to any
 * installed extension.
 *
 * [trackerName] is set only for tracker-origin entries and lets the merge step round-robin tracker
 * slots fairly across trackers instead of letting whichever pushed first dominate.
 *
 * Equality is by [SManga.url] only, so a `LinkedHashSet` keeps the first-seen insertion (normally
 * source-native, which usually completes first).
 *
 * R2 will extend this with an alt-title set (for stronger cross-source dedup) and an origin label
 * (for the browse-screen "because you're reading X" grouping).
 */
class RelatedMangaCandidate(
    val sourceId: Long,
    val trackerName: String?,
    val manga: SManga,
) {
    override fun equals(other: Any?): Boolean =
        other is RelatedMangaCandidate && manga.url == other.manga.url

    override fun hashCode(): Int = manga.url.hashCode()
}
