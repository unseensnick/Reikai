package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import reikai.domain.recommendation.taste.AdultContent

/**
 * Base contract for a tracker-backed recommendation provider: given a manga the user is viewing,
 * return titles that tracker considers similar. Dispatch prefers an id lookup when the user already
 * tracks the manga, else resolving the id through ONE title search, never a fan-out. Providers hit a
 * public endpoint over the shared client from [RecommendationProviders], never the tracker's
 * authenticated client, which would throw for a logged-out user. Candidates are tagged
 * [RECOMMENDS_SOURCE]: their URL fits no installed extension, so a tap routes through global search.
 */
abstract class TrackerRecommendations {

    /** `parseAs` is a context function, so call sites wrap it in `with(json) { ... }`. */
    protected abstract val json: Json

    abstract val trackerName: String

    /** The tracker's stable id (from `TrackerManager`), stamped onto every candidate for id matching. */
    abstract val trackerId: Long

    abstract suspend fun getRecsById(remoteId: Long): List<RelatedMangaCandidate>

    abstract suspend fun getRecsBySearch(title: String): List<RelatedMangaCandidate>

    suspend fun fetch(remoteId: Long?, title: String): List<RelatedMangaCandidate> =
        if (remoteId != null) getRecsById(remoteId) else getRecsBySearch(title)

    /**
     * The tracker's view of one media the user already tracks: recommendations plus genres, for the
     * taste-driven injection, which needs the tracker's own "similar" list (cross-rec) and its clean
     * genres (tag-search). The default fetches recommendations only; a provider that can return
     * genres in the same call overrides this.
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
        // Both default to "we were not told". A provider whose recommendation payload carries an
        // adult flag or genres passes them, and only then can the adult filter screen its results.
        adult: AdultContent = AdultContent.UNKNOWN,
        genres: List<String> = emptyList(),
    ): RelatedMangaCandidate = RelatedMangaCandidate(
        sourceId = RECOMMENDS_SOURCE,
        trackerName = trackerName,
        manga = SManga.create().apply {
            this.url = url
            this.title = title
            this.thumbnail_url = thumbnailUrl
            this.genre = genres.joinToString(", ").ifBlank { null }
            this.initialized = true
        },
        altTitles = altTitles,
        origin = RecommendationOrigin.Tracker(trackerName),
        trackerId = trackerId,
        remoteId = remoteId,
        adult = adult,
    )
}
