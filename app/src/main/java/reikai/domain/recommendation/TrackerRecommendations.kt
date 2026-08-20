package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json

/**
 * Base contract for a tracker-backed recommendation provider: given a manga the user is viewing,
 * return titles that tracker considers similar. Dispatch prefers an id-based lookup when the user
 * already tracks the manga, otherwise resolving the id through ONE title search, never a fan-out.
 * Providers target a single public recommendations endpoint over the shared rate-limited client from
 * [RecommendationsFetcher], never the tracker's authenticated client, which would throw for a
 * logged-out user. Candidates are tagged [RECOMMENDS_SOURCE], their URL belonging to no installed
 * extension, so a tap routes through global search.
 */
abstract class TrackerRecommendations {

    /** Shared JSON for response parsing, handed down by [RecommendationProviders]. `parseAs` is a
     *  context function, so call sites wrap it in `with(json) { ... }`. */
    protected abstract val json: Json

    abstract val trackerName: String

    /** The tracker's stable id (from `TrackerManager`), stamped onto every candidate for id matching. */
    abstract val trackerId: Long

    abstract suspend fun getRecsById(remoteId: Long): List<RelatedMangaCandidate>

    abstract suspend fun getRecsBySearch(title: String): List<RelatedMangaCandidate>

    suspend fun fetch(remoteId: Long?, title: String): List<RelatedMangaCandidate> =
        if (remoteId != null) getRecsById(remoteId) else getRecsBySearch(title)

    /**
     * The tracker's view of one media the user already tracks (exact [remoteId]): its recommendations
     * plus its genres. Used by the taste-driven injection, which needs the tracker's own "similar"
     * list (cross-rec) and the tracker's clean genres (tag-search). Default fetches recommendations
     * only; providers that can return genres in the same call override this.
     */
    open suspend fun getMediaContext(remoteId: Long): MediaContext =
        MediaContext(genres = emptyList(), recommendations = getRecsById(remoteId))

    data class MediaContext(
        val genres: List<String>,
        val recommendations: List<RelatedMangaCandidate>,
    )

    protected fun candidate(
        url: String,
        title: String,
        thumbnailUrl: String?,
        remoteId: Long? = null,
        altTitles: List<String> = emptyList(),
    ): RelatedMangaCandidate = RelatedMangaCandidate(
        sourceId = RECOMMENDS_SOURCE,
        trackerName = trackerName,
        manga = SManga.create().apply {
            this.url = url
            this.title = title
            this.thumbnail_url = thumbnailUrl
            this.initialized = true
        },
        altTitles = altTitles,
        origin = RecommendationOrigin.Tracker(trackerName),
        trackerId = trackerId,
        remoteId = remoteId,
    )
}
