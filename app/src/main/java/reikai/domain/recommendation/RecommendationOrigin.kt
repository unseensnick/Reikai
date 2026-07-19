package reikai.domain.recommendation

/**
 * Where a related-manga candidate came from, used by the recommendations browse screen to group results into
 * explainable sections ("From your AniList recommendations", "Because you're reading X").
 */
sealed interface RecommendationOrigin {
    /** The current source's own related / keyword-search suggestions. */
    data object SourceNative : RecommendationOrigin

    /** A tracker's per-manga recommendation endpoint (AniList, MAL, MangaUpdates, Shikimori). */
    data class Tracker(val trackerName: String) : RecommendationOrigin

    /** A taste-driven cross-recommendation seeded from a highly-rated tracked title. */
    data class CrossRec(val fromTitle: String) : RecommendationOrigin

    /** A taste-driven source search on one of the user's top profile tags. */
    data class TagSearch(val tag: String) : RecommendationOrigin
}
