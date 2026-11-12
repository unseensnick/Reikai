package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** A local series whose folder is gone, with the folders that hold its chapter files for the user to pick from. */
@Composable
fun RelinkSeriesDialog(
    entryTitle: String,
    candidates: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    LinkFolderDialog(
        message = stringResource(MR.strings.local_series_missing_message, entryTitle),
        folders = candidates,
        onDismissRequest = onDismissRequest,
        onLink = {
            onDismissRequest()
            onConfirm(it)
        },
    )
}

/**
 * A folder of [folders] to link something gone missing to, with [message] above them and [more] below. One is only
 * ever linked on the user's own pick, even when it is the only one: a folder holding the same file names is
 * evidence, not identity. A new list clears the pick. Both the series relink and a missing volume use it.
 */
@Composable
internal fun LinkFolderDialog(
    message: String,
    folders: List<String>?,
    onDismissRequest: () -> Unit,
    onLink: (String) -> Unit,
    more: @Composable ColumnScope.() -> Unit = {},
) {
    var picked by rememberSaveable(folders) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.local_series_missing_title)) },
        text = {
            Column {
                Text(message)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(folders.orEmpty(), key = { it }) { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { picked = folder },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = folder == picked, onClick = { picked = folder })
                            Text(folder)
                        }
                    }
                }
                more()
            }
        },
        confirmButton = {
            TextButton(enabled = picked != null, onClick = { picked?.let(onLink) }) {
                Text(stringResource(MR.strings.action_link_folder))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}
