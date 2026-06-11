package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.domain.track.model.Track

/**
 * Maps a local [Track]'s tracker-specific status id to a generic [TrackStatus], for the
 * recommendation anti-echo filter (hide already-tracked suggestions by status).
 *
 * Reading / rereading / completed use the Tracker interface's generic getters, correct for every
 * tracker. On-hold / dropped / plan-to-read have no generic getter, so they are mapped from each
 * external tracker's known status ids. Trackers without these semantics (Komga / Kavita / Suwayomi)
 * fall through to [TrackStatus.UNKNOWN] for those three.
 */
class LocalTrackStatusMapper(
    private val trackerManager: TrackerManager,
) {

    fun map(track: Track): TrackStatus {
        val tracker = trackerManager.get(track.trackerId) ?: return TrackStatus.UNKNOWN
        return when (track.status) {
            tracker.getReadingStatus(), tracker.getRereadingStatus() -> TrackStatus.READING
            tracker.getCompletionStatus() -> TrackStatus.COMPLETED
            else -> mapExtended(track.trackerId, track.status)
        }
    }

    private fun mapExtended(trackerId: Long, status: Long): TrackStatus = when (trackerId) {
        trackerManager.bangumi.id -> when (status) {
            1L -> TrackStatus.PLAN_TO_READ
            4L -> TrackStatus.ON_HOLD
            5L -> TrackStatus.DROPPED
            else -> TrackStatus.UNKNOWN
        }
        trackerManager.myAnimeList.id -> when (status) {
            3L -> TrackStatus.ON_HOLD
            4L -> TrackStatus.DROPPED
            6L -> TrackStatus.PLAN_TO_READ
            else -> TrackStatus.UNKNOWN
        }
        // AniList / Kitsu / Shikimori share on-hold=3, dropped=4, plan-to-read=5.
        trackerManager.aniList.id, trackerManager.kitsu.id, trackerManager.shikimori.id -> when (status) {
            3L -> TrackStatus.ON_HOLD
            4L -> TrackStatus.DROPPED
            5L -> TrackStatus.PLAN_TO_READ
            else -> TrackStatus.UNKNOWN
        }
        else -> TrackStatus.UNKNOWN
    }
}
