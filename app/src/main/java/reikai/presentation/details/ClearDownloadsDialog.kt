package reikai.presentation.details

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirms clearing an entry's downloaded chapters. [sourceName] names the one grouped source being
 * cleared, or is null when the unified view is on and every source goes. Only files are removed:
 * chapters, read state, bookmarks and history are untouched, which is why the message says the
 * chapters can simply be downloaded again.
 */
@Composable
fun ClearDownloadsDialog(
    sourceName: String?,
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
                    onConfirm()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        text = {
            Text(
                text = sourceName
                    ?.let { stringResource(MR.strings.confirm_clear_downloads_source, it) }
                    ?: stringResource(MR.strings.confirm_clear_downloads),
            )
        },
    )
}
