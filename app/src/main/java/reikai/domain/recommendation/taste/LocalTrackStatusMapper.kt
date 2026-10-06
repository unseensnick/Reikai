package reikai.domain.recommendation.taste

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Maps a local [Track]'s tracker-specific status id to a generic [TrackStatus], for the
 * recommendation anti-echo filter.
 */
@Inject
class LocalTrackStatusMapper(
    private val trackerManager: TrackerManager,
) {

    fun map(track: Track): TrackStatus =
        trackerManager.get(track.trackerId)?.trackStatusOf(track.status) ?: TrackStatus.UNKNOWN
}

/**
 * Reading / rereading / completed use the Tracker interface's generic getters. On-hold / dropped /
 * plan-to-read have none, so they are read off the label the tracker itself shows for the status.
 * A status with no reading meaning (a self-hosted server's unread) stays [TrackStatus.UNKNOWN].
 */
fun Tracker.trackStatusOf(status: Long): TrackStatus = when (status) {
    getReadingStatus(), getRereadingStatus() -> TrackStatus.READING
    getCompletionStatus() -> TrackStatus.COMPLETED
    else -> STATUS_BY_LABEL[getStatus(status)] ?: TrackStatus.UNKNOWN
}

/**
 * The [TrackStatus] of a remote list token, read through the tracker's own local-to-remote mapping
 * run backwards over [Tracker.getStatusList]. A token no local status writes is [TrackStatus.UNKNOWN].
 */
fun <T : Any> Tracker.trackStatusOfRemote(raw: T?, toRemote: (DbTrack) -> T?): TrackStatus {
    if (raw == null) return TrackStatus.UNKNOWN
    val local = getStatusList().firstOrNull { status ->
        toRemote(DbTrack.create(id).also { it.status = status }) == raw
    } ?: return TrackStatus.UNKNOWN
    return trackStatusOf(local)
}

private val STATUS_BY_LABEL = mapOf(
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
