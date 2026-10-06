package reikai.presentation.details

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Mihon's details-heart prompt after a removal: offers to delete the downloads of the [removed] entries
 * that have any. Both content types' heart and Manage sources ask through here.
 */
suspend fun <T> SnackbarHostState.offerToDeleteDownloads(
    context: Context,
    removed: List<T>,
    hasDownloads: suspend (T) -> Boolean,
    delete: suspend (T) -> Unit,
) {
    val withDownloads = removed.filter { hasDownloads(it) }
    if (withDownloads.isEmpty()) return
    val result = showSnackbar(
        message = context.stringResource(MR.strings.delete_downloads_for_manga),
        actionLabel = context.stringResource(MR.strings.action_delete),
        withDismissAction = true,
    )
    if (result == SnackbarResult.ActionPerformed) withDownloads.forEach { delete(it) }
}

/**
 * Mihon's prompt after the first download of an entry outside the library. [isInLibrary] is read again
 * on Add, since the entry may have joined the library while the snackbar was up.
 */
suspend fun SnackbarHostState.offerAddToLibrary(context: Context, isInLibrary: () -> Boolean, add: () -> Unit) {
    val result = showSnackbar(
        message = context.stringResource(MR.strings.snack_add_to_library),
        actionLabel = context.stringResource(MR.strings.action_add),
        withDismissAction = true,
    )
    if (result == SnackbarResult.ActionPerformed && !isInLibrary()) add()
}
