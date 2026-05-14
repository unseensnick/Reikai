package eu.kanade.tachiyomi.ui.manga.related

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Pool element for the related-mangas carousel. Pairs an [SManga] with the source id it should
 * be treated as coming from when clicked — either the current source for source-native / keyword
 * suggestions, or [eu.kanade.tachiyomi.data.recommendation.RECOMMENDS_SOURCE] for tracker
 * recommendations whose URL doesn't belong to any installed extension.
 *
 * [trackerName] is only set for tracker-origin entries (AniList / MyAnimeList / MangaUpdates) and
 * lets the merge step in `MangaDetailsPresenter.mergeForDisplay` round-robin tracker slots fairly
 * across trackers instead of letting whichever pushed first dominate.
 *
 * Equality is by url only so the [LinkedHashSet] used in
 * [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter.fetchRelatedMangasFromSource] keeps the
 * first-seen insertion (which is normally source-native — it usually completes first).
 */
class RelatedMangaCandidate(val sourceId: Long, val trackerName: String?, val manga: SManga) {
    override fun equals(other: Any?): Boolean =
        other is RelatedMangaCandidate && manga.url == other.manga.url

    override fun hashCode(): Int = manga.url.hashCode()
}
