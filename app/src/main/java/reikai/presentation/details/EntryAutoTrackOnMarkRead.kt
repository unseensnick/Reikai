package reikai.presentation.details

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Pushing progress to an entry's trackers after chapters are marked read from its details list, for both
 * content types. Honours the never / always / ask preference, refreshes first so the decision is made
 * against current remote values, and reports a failed refresh the same way on both types.
 *
 * The three per-type halves come in as lambdas, since manga and novel tracks live in different tables:
 * [refresh] and [lastReadPerTracker] read them, [pushProgress] writes. Both sides read the whole merge
 * group, so a tracker bound on one source still advances when a chapter of a sibling source is read.
 */
class EntryAutoTrackOnMarkRead(
    private val context: Context,
    private val snackbarHostState: SnackbarHostState,
    private val trackerManager: TrackerManager,
    private val trackPreferences: TrackPreferences,
    private val refresh: suspend (entryId: Long) -> List<Pair<Tracker?, Throwable>>,
    private val lastReadPerTracker: suspend (entryId: Long) -> List<Double>,
    private val pushProgress: suspend (entryId: Long, chapterNumber: Double) -> Unit,
) {

    suspend fun await(entryId: Long, chapterNumbers: List<Double>) {
        if (chapterNumbers.isEmpty() || trackerManager.loggedInTrackers().isEmpty()) return
        val autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()
        if (autoTrackState == AutoTrackState.NEVER) return

        reportFailures(entryId, refresh(entryId))

        val furthestRead = chapterNumbers.max()
        if (lastReadPerTracker(entryId).none { furthestRead > it }) return

        if (autoTrackState == AutoTrackState.ALWAYS) {
            pushProgress(entryId, furthestRead)
            withUIContext {
                context.toast(context.stringResource(MR.strings.trackers_updated_summary, furthestRead.toInt()))
            }
            return
        }

        val result = snackbarHostState.showSnackbar(
            message = context.stringResource(MR.strings.confirm_tracker_update, furthestRead.toInt()),
            actionLabel = context.stringResource(MR.strings.action_ok),
            duration = SnackbarDuration.Short,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) pushProgress(entryId, furthestRead)
    }

    private suspend fun reportFailures(entryId: Long, results: List<Pair<Tracker?, Throwable>>) {
        results.filter { it.first != null }.forEach { (tracker, e) ->
            logcat(LogPriority.ERROR, e) { "Failed to refresh track data entryId=$entryId for ${tracker!!.id}" }
            withUIContext {
                context.toast(context.stringResource(MR.strings.track_error, tracker!!.name, e.message ?: ""))
            }
        }
    }
}
