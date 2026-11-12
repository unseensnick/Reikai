package reikai.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import reikai.data.local.CombinableSeries
import reikai.presentation.details.AlsoOrganizeFiles
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Pick the series the others join as volumes, and see what happens to them before anything moves. */
@Composable
fun CombineVolumesDialog(
    series: List<CombinableSeries>,
    canOrganize: Boolean,
    onDismissRequest: () -> Unit,
    // organize: the files are to move into the kept series' folder after, through Organize files' own preview.
    onConfirm: (kept: CombinableSeries, organize: Boolean) -> Unit,
) {
    var kept by remember { mutableStateOf(series.first()) }
    var alsoOrganize by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_combine_volumes)) },
        text = {
            Column {
                Text(stringResource(MR.strings.combine_volumes_keep))
                series.forEach { one ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { kept = one },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = one == kept, onClick = { kept = one })
                        Text(one.title)
                    }
                }
                Text(
                    stringResource(MR.strings.combine_volumes_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AlsoOrganizeFiles(canOrganize, alsoOrganize) { alsoOrganize = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismissRequest()
                onConfirm(kept, alsoOrganize)
            }) { Text(stringResource(MR.strings.action_combine)) }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}
