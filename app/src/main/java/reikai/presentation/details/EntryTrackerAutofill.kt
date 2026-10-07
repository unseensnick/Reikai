package reikai.presentation.details

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import reikai.data.track.MetadataAccess
import reikai.data.track.TrackerEntryMissingException
import reikai.data.track.TrackerSignedOutException
import reikai.util.runCatchingCancellable
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

/**
 * The tag chips after "Fill from tracker", for manga and novels alike: the tags already there stay, since
 * they may be the user's own, and the tracker's genres are appended. A genre matching a tag bar case or
 * surrounding spaces is the same genre, and the spelling already on the entry wins.
 */
fun mergeTrackerGenres(current: List<String>, fromTracker: List<String>): List<String> =
    (current + fromTracker).filter { it.isNotBlank() }.distinctBy { it.trim().lowercase() }

/** The tracker has no entry at the bound id, the one failure "Fill from tracker" words its own way. */
fun isMissingOnTracker(error: Throwable): Boolean =
    error is TrackerEntryMissingException || (error is HttpException && error.code == 404)

/**
 * One "Fill from tracker" fetch. A tracker whose metadata needs a login is refused before it fetches
 * while signed out; a public one fills regardless. Cancellation is not a failure: dismissing the dialog
 * mid-fetch cancels it, and reporting that would toast a tracker error the tracker never raised.
 */
suspend fun <T> runTrackerFill(
    tracker: Tracker,
    fetch: suspend () -> T,
    onFilled: (T) -> Unit,
    onFailed: (Throwable) -> Unit,
) {
    if (tracker.metadataAccess == MetadataAccess.SignedIn && !tracker.isLoggedIn) {
        return onFailed(TrackerSignedOutException(tracker.name))
    }
    runCatchingCancellable { fetch() }.onSuccess(onFilled).onFailure(onFailed)
}
