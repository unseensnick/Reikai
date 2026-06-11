package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy

/**
 * Base contract for a tracker-backed recommendation provider: given a manga the user is viewing,
 * return titles that tracker considers similar. Dispatch mirrors the tracker UIs: prefer an
 * id-based lookup when the user already tracks this manga, otherwise resolve the id via one title
 * search (never a fan-out).
 *
 * Concrete providers target a single public "similar / recommendations" endpoint and use the
 * shared rate-limited client from [RecommendationsFetcher]; they do not use the tracker's
 * authenticated client (Shikimori's would throw for a logged-out user, see [ShikimoriRecommendations]).
 *
 * Candidates are tagged [RECOMMENDS_SOURCE] (their URL belongs to no installed extension, so a tap
 * routes through global search) and carry [RecommendationOrigin.Tracker] for the R5 grouping.
 */
abstract class TrackerRecommendations {

    /** Shared JSON for response parsing. `parseAs` is a context function, so call sites wrap it in
     *  `with(json) { ... }` (mirrors the tracker `*Api` classes). */
    protected val json: Json by injectLazy()

    abstract val trackerName: String

    abstract suspend fun getRecsById(remoteId: Long): List<RelatedMangaCandidate>

    abstract suspend fun getRecsBySearch(title: String): List<RelatedMangaCandidate>

    suspend fun fetch(remoteId: Long?, title: String): List<RelatedMangaCandidate> =
        if (remoteId != null) getRecsById(remoteId) else getRecsBySearch(title)

    protected fun candidate(
        url: String,
        title: String,
        thumbnailUrl: String?,
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
    )
}
