package reikai.presentation.details

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.domain.track.model.Track

/**
 * The bound trackers eligible for "Fill from tracker" on the shared edit-info dialog: resolve each
 * [Track] to its [Tracker] and drop self-hosted enhanced trackers (they can't autofill). Shared so the
 * manga and novel details models apply one eligibility rule; each side only supplies its own track list.
 */
fun buildTrackerAutofillCandidates(
    tracks: List<Track>,
    trackerManager: TrackerManager,
): List<Pair<Track, Tracker>> =
    tracks.mapNotNull { track -> trackerManager.get(track.trackerId)?.let { track to it } }
        .filterNot { (_, tracker) -> tracker is EnhancedTracker }
