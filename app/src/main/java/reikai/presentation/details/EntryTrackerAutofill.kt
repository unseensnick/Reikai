package reikai.presentation.details

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import reikai.data.track.MetadataAccess
import reikai.data.track.TrackerSignedOutException
import reikai.presentation.track.TrackerError
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

/** Why "Fill from tracker" found nothing to fill, in the terms the dialog tells the reader. */
sealed interface TrackerAutofillError {
    /** The tracker has no entry at the bound id, which it answers with a 404. */
    data object NotFound : TrackerAutofillError

    /** No usable login for a tracker whose metadata needs one. */
    data object SignedOut : TrackerAutofillError

    /** Any other failure, with its message, or null where it carries none worth showing. */
    data class Failed(val message: String?) : TrackerAutofillError
}

fun trackerAutofillError(error: Throwable): TrackerAutofillError = when {
    error is HttpException && error.code == 404 -> TrackerAutofillError.NotFound
    TrackerError.of(error, isOnline = true) == TrackerError.SignedOut -> TrackerAutofillError.SignedOut
    else -> TrackerAutofillError.Failed(error.message?.takeIf { it.isNotBlank() })
}

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
