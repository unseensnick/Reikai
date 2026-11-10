package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A local series whose folder is gone, with the folders that hold its chapter files for the user to pick
 * from. One is only ever linked on a pick: a folder holding the same file names is evidence, not identity.
 */
@Composable
fun RelinkSeriesDialog(
    entryTitle: String,
    candidates: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var picked by rememberSaveable { mutableStateOf(candidates.singleOrNull()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.local_series_missing_title)) },
        text = {
            Column {
                Text(stringResource(MR.strings.local_series_missing_message, entryTitle))
                candidates.forEach { folder ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { picked = folder },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = folder == picked, onClick = { picked = folder })
                        Text(folder)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked != null,
                onClick = {
                    onDismissRequest()
                    picked?.let(onConfirm)
                },
            ) {
                Text(stringResource(MR.strings.action_link_folder))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
