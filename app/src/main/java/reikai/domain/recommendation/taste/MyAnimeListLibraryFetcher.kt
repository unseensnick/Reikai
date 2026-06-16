package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryItem
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryListStatus
import reikai.domain.recommendation.ReikaiRecommendationPreferences

/**
 * Pulls the user's full MyAnimeList manga list via the official API (`/users/@me/mangalist`, paged
 * 250/entry) and normalizes each entry into a [TrackedEntry].
 *
 * Score: MAL's fixed 0..10 integer scale, divided by 10; raw 0 (unrated) becomes -1.0. Status: MAL
 * exposes `is_rereading` separately, which overrides to READING (matches AniList's REPEATING rule).
 * Tags: only `genres` is exposed inline (no AniList-style tag array), so the signal is coarser.
 */
class MyAnimeListLibraryFetcher(
    private val myAnimeList: MyAnimeList,
    private val preferences: ReikaiRecommendationPreferences,
) : TrackerLibraryFetcher {

    override val trackerId: Long = myAnimeList.id

    override fun isEnabled(): Boolean =
        preferences.pullLibraryFromMyAnimeList.get() && myAnimeList.isLoggedIn

    override suspend fun fetchLibrary(): List<TrackedEntry> =
        myAnimeList.getUserLibrary().map { it.toTrackedEntry() }

    private fun MALLibraryItem.toTrackedEntry(): TrackedEntry = TrackedEntry(
        trackerId = trackerId,
        remoteId = node.id,
        title = node.title,
        score = normalizeTrackerScore(listStatus?.score, 10),
        status = mapStatus(listStatus),
        tags = node.genres.map { it.name.toTagKey() }.filter { it.isNotEmpty() }.distinct(),
        // MAL's remote id IS the MAL id, so cross-tracker dedup can collapse AniList entries that
        // point here via Media.idMal.
        malId = node.id,
    )

    private fun mapStatus(listStatus: MALLibraryListStatus?): TrackStatus {
        if (listStatus == null) return TrackStatus.UNKNOWN
        if (listStatus.isRereading) return TrackStatus.READING
        return when (listStatus.status) {
            "reading" -> TrackStatus.READING
            "completed" -> TrackStatus.COMPLETED
            "on_hold" -> TrackStatus.ON_HOLD
            "dropped" -> TrackStatus.DROPPED
            "plan_to_read" -> TrackStatus.PLAN_TO_READ
            else -> TrackStatus.UNKNOWN
        }
    }
}
