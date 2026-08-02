package reikai.presentation.browse.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirm removing a favorited browse result from the library, shared by every manga and novel
 * browse surface (per-source browse, global search, the MangaDex follows screen). Replaces Mihon's
 * `RemoveMangaDialog` (deleted, see the off-path manifest) and the novel twin it had.
 */
@Composable
fun EntryRemoveDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.are_you_sure))
        },
        text = {
            Text(text = stringResource(MR.strings.remove_manga, title))
        },
    )
}
