package reikai.presentation.details

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import reikai.presentation.migrate.flow.runCatchingCancellable
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

/** Why "Fill from tracker" found nothing to fill, in the terms the dialog tells the reader. */
sealed interface TrackerAutofillError {
    /** The tracker has no entry at the bound id, which it answers with a 404. */
    data object NotFound : TrackerAutofillError

    /** Any other failure, with its message, or null where it carries none worth showing. */
    data class Failed(val message: String?) : TrackerAutofillError
}

fun trackerAutofillError(error: Throwable): TrackerAutofillError = when {
    error is HttpException && error.code == 404 -> TrackerAutofillError.NotFound
    else -> TrackerAutofillError.Failed(error.message?.takeIf { it.isNotBlank() })
}

/** One "Fill from tracker" fetch. Cancellation is not a failure: dismissing the dialog mid-fetch cancels
 *  it, and reporting that would toast a tracker error for something the tracker never did. */
suspend fun <T> runTrackerFill(fetch: suspend () -> T, onFilled: (T) -> Unit, onFailed: (Throwable) -> Unit) {
    runCatchingCancellable { fetch() }.onSuccess(onFilled).onFailure(onFailed)
}
