package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionItem
import reikai.domain.recommendation.ReikaiRecommendationPreferences

/**
 * Pulls the user's full Bangumi manga collection (`subject_type=1`, paged 50/entry) and normalizes
 * each entry into a [TrackedEntry].
 *
 * Score: Bangumi's `rate` 0..10, divided by 10; 0 (unrated) becomes -1.0. Status: the collection
 * `type` matches Bangumi's status ids (1 plan, 2 completed, 3 reading, 4 on-hold, 5 dropped). Tags:
 * the subject's `tags` names (no count threshold in v1). Title prefers `name_cn`, then `name`.
 */
class BangumiLibraryFetcher(
    private val bangumi: Bangumi,
    private val preferences: ReikaiRecommendationPreferences,
) : TrackerLibraryFetcher {

    override val trackerId: Long = bangumi.id

    override fun isEnabled(): Boolean =
        preferences.pullLibraryFromBangumi.get() && bangumi.isLoggedIn

    override suspend fun fetchLibrary(): List<TrackedEntry> =
        bangumi.getUserLibrary().map { it.toTrackedEntry() }

    private fun BGMCollectionItem.toTrackedEntry(): TrackedEntry = TrackedEntry(
        trackerId = trackerId,
        remoteId = subjectId,
        title = subject?.nameCn?.ifBlank { subject.name } ?: subject?.name.orEmpty(),
        score = normalizeTrackerScore(rate, 10),
        status = mapStatus(type),
        tags = subject?.tags
            ?.map { it.name.toTagKey() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty(),
    )

    private fun mapStatus(type: Int): TrackStatus = when (type) {
        1 -> TrackStatus.PLAN_TO_READ
        2 -> TrackStatus.COMPLETED
        3 -> TrackStatus.READING
        4 -> TrackStatus.ON_HOLD
        5 -> TrackStatus.DROPPED
        else -> TrackStatus.UNKNOWN
    }
}
