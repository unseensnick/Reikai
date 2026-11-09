package reikai.presentation.components

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import logcat.LogPriority
import reikai.data.local.LocalMoveResult
import reikai.data.local.LocalVolumeMove
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Reports [move]: a failure as a toast, a move that went through as [message] with an Undo, as merging
 * does. Answers whether the move stands. Its tidy waits for the snackbar to close, leaving the screen
 * included, since an Undo still needs what it deletes.
 */
suspend fun SnackbarHostState.reportVolumeMove(context: Context, move: LocalVolumeMove, message: String): Boolean {
    (move.result as? LocalMoveResult.Failed)?.let {
        logcat(LogPriority.ERROR, it.error) { "Moving local volumes failed" }
        context.toast(MR.strings.local_volumes_move_failed)
        return false
    }
    var undoing = false
    try {
        currentSnackbarData?.dismiss()
        undoing = showSnackbar(
            message = message,
            actionLabel = context.stringResource(MR.strings.action_undo),
            duration = SnackbarDuration.Short,
            withDismissAction = true,
        ) == SnackbarResult.ActionPerformed
    } finally {
        if (!undoing) move.finish()
    }
    if (!undoing) return true
    val undone = withContext(NonCancellable) { move.undo() }
    if (undone is LocalMoveResult.Failed) {
        logcat(LogPriority.ERROR, undone.error) { "Undoing a local volume move failed" }
        context.toast(MR.strings.undo_volumes_failed)
        move.finish()
    }
    return undone is LocalMoveResult.Failed
}
