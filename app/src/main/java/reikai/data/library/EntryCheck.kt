package reikai.data.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat

/** What one entry's step in a library update came to. */
sealed interface EntryCheck {
    data object Skipped : EntryCheck
    data object Checked : EntryCheck
    data class Failed(val message: String) : EntryCheck
}

/**
 * One entry's step in a library update, for the manga and novel jobs both. An entry that left the
 * library after the run listed it is skipped before its progress shows, as Mihon's job skips it; a
 * check then clears the entry's recorded update error or records its failure, and neither bookkeeping
 * write can fail the entry. As in Mihon, anything the check throws is that entry's failure, a source's
 * own timeout included; only a cancelled run stays cancelled, and it records nothing.
 */
suspend fun checkUpdateEntry(
    stillInLibrary: suspend () -> Boolean,
    progress: suspend (suspend () -> Unit) -> Unit,
    trackErrors: Boolean,
    clearError: suspend () -> Unit,
    recordError: suspend (String) -> Unit,
    failureMessage: (Throwable) -> String,
    check: suspend () -> Unit,
): EntryCheck {
    if (!stillInLibrary()) return EntryCheck.Skipped
    var outcome: EntryCheck = EntryCheck.Checked
    progress {
        outcome = try {
            check()
            if (trackErrors) runCatchingCancellable { clearError() }
            EntryCheck.Checked
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            e.logcat(LogPriority.ERROR, e)
            val message = failureMessage(e)
            if (trackErrors) runCatchingCancellable { recordError(message) }
            EntryCheck.Failed(message)
        }
    }
    return outcome
}
