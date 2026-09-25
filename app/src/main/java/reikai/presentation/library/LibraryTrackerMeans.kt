package reikai.presentation.library

import eu.kanade.tachiyomi.data.track.Tracker
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
        val scores = memberIds.flatMap { tracksById[it].orEmpty() }
            .distinctBy { it.trackerId }
            .mapNotNull { trackers[it.trackerId]?.get10PointScore(it)?.takeIf { s -> s > 0.0 } }
        if (scores.isNotEmpty()) put(rowId, scores.average())
    }
}
