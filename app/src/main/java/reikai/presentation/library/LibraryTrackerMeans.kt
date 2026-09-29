package reikai.presentation.library

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.domain.track.model.Track

/**
 * Mean 0-10 tracker score per library row, the one rule both content types sort by. Keyed by the row's
 * own id, unscored rows absent (callers default to -1.0). [membersByRow] is each row's merged group, so
 * a tracker on any grouped source counts once; only [trackers] (the logged-in ones) score, and unrated
 * (<= 0) scores drop. Guarding on the mapped scores rather than the raw list fixes the upstream bug
 * where an all-logged-out list averaged to NaN and sorted above every real score.
 */
fun libraryTrackerMeans(
    membersByRow: Map<Long, List<Long>>,
    tracksById: Map<Long, List<Track>>,
    trackers: Map<Long, Tracker>,
): Map<Long, Double> = buildMap {
    membersByRow.forEach { (rowId, memberIds) ->
        val scores = mergedGroupTracks(memberIds, tracksById)
            .mapNotNull { trackers[it.trackerId]?.get10PointScore(it)?.takeIf { s -> s > 0.0 } }
        if (scores.isNotEmpty()) put(rowId, scores.average())
    }
}

/**
 * A merged row's tracks, which every tracker-reading library rule reads (filter, sort, grouping), so a
 * tracker bound on any grouped source counts. One per tracker, the first member's winning.
 */
fun mergedGroupTracks(memberIds: List<Long>, tracksById: Map<Long, List<Track>>): List<Track> =
    memberIds.flatMap { tracksById[it].orEmpty() }.distinctBy { it.trackerId }

/** The tracking status a row is grouped under: the first logged-in tracker's among [groupTracks]. */
fun groupTrackStatus(
    groupTracks: List<Track>,
    loggedInTrackerIds: Set<Long>,
    trackerManager: TrackerManager,
): StringResource? {
    val track = groupTracks.firstOrNull { it.trackerId in loggedInTrackerIds } ?: return null
    return trackerManager.get(track.trackerId)?.getStatus(track.status)
}
