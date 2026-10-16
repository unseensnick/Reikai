package reikai.presentation.library

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import reikai.domain.track.canonicalTracksPerTracker
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
        libraryTrackerMean(memberIds, tracksById, trackers)?.let { put(rowId, it) }
    }
}

/** One row's [libraryTrackerMeans] entry, null when it has no score. */
fun libraryTrackerMean(
    memberIds: List<Long>,
    tracksById: Map<Long, List<Track>>,
    trackers: Map<Long, Tracker>,
): Double? =
    mergedGroupTracks(memberIds, tracksById)
        .mapNotNull { trackers[it.trackerId]?.get10PointScore(it)?.takeIf { s -> s > 0.0 } }
        .takeIf { it.isNotEmpty() }
        ?.average()

/** The names of a row's trackers among [trackers], the logged-in ones, across its merged group. */
fun libraryTrackerNames(memberIds: List<Long>, tracksById: Map<Long, List<Track>>, trackers: Map<Long, Tracker>) =
    mergedGroupTracks(memberIds, tracksById).mapNotNull { trackers[it.trackerId]?.name }

/**
 * A merged row's tracks, which every tracker-reading library rule reads (filter, sort, grouping), so a
 * tracker bound on any grouped source counts. One per tracker, picked as the tracking sheet picks it.
 */
fun mergedGroupTracks(memberIds: List<Long>, tracksById: Map<Long, List<Track>>): List<Track> =
    canonicalTracksPerTracker(memberIds.flatMap { tracksById[it].orEmpty() }, Track::trackerId, Track::lastChapterRead)

/** The tracking status a row is grouped under: the first logged-in tracker's among [groupTracks]. */
fun groupTrackStatus(
    groupTracks: List<Track>,
    loggedInTrackerIds: Set<Long>,
    trackerManager: TrackerManager,
): StringResource? {
    val track = groupStatusTrack(groupTracks, loggedInTrackerIds) ?: return null
    return trackerManager.get(track.trackerId)?.getStatus(track.status)
}

/** The track whose status a row's tracking status is, for the library's grouping and Statistics alike. */
fun groupStatusTrack(groupTracks: List<Track>, loggedInTrackerIds: Set<Long>): Track? =
    groupTracks.firstOrNull { it.trackerId in loggedInTrackerIds }
