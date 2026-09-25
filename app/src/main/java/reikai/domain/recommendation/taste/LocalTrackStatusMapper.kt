package reikai.domain.recommendation.taste

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR

/**
 * Maps a local [Track]'s tracker-specific status id to a generic [TrackStatus], for the
 * recommendation anti-echo filter. Reading / rereading / completed use the Tracker interface's
 * generic getters. On-hold / dropped / plan-to-read have none, so they are read off the label the
 * tracker itself shows for the status, which covers a new tracker that reuses the stock labels with no
 * edit here. A status with no reading meaning (a self-hosted server's unread) stays [TrackStatus.UNKNOWN].
 */
@Inject
class LocalTrackStatusMapper(
    private val trackerManager: TrackerManager,
) {

    fun map(track: Track): TrackStatus {
        val tracker = trackerManager.get(track.trackerId) ?: return TrackStatus.UNKNOWN
        return when (track.status) {
            tracker.getReadingStatus(), tracker.getRereadingStatus() -> TrackStatus.READING
            tracker.getCompletionStatus() -> TrackStatus.COMPLETED
            else -> STATUS_BY_LABEL[tracker.getStatus(track.status)] ?: TrackStatus.UNKNOWN
        }
    }

    private companion object {
        val STATUS_BY_LABEL = mapOf(
            MR.strings.on_hold to TrackStatus.ON_HOLD,
            MR.strings.on_hold_list to TrackStatus.ON_HOLD,
            MR.strings.paused to TrackStatus.ON_HOLD,
            MR.strings.dropped to TrackStatus.DROPPED,
            // MangaUpdates' Unfinished list holds series its reader stopped on.
            MR.strings.unfinished_list to TrackStatus.DROPPED,
            MR.strings.plan_to_read to TrackStatus.PLAN_TO_READ,
            MR.strings.wish_list to TrackStatus.PLAN_TO_READ,
            MR.strings.considering to TrackStatus.PLAN_TO_READ,
        )
    }
}
