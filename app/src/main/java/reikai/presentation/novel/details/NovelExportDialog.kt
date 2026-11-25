package reikai.presentation.novel.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Export as EPUB: how many chapters are on the device, a way to download the rest, whether to keep the
 * pictures, and which run of chapters to write. [onExport] asks where to save; the screen holds that
 * launcher, since this dialog is gone when the process comes back from the system picker.
 */
@Composable
fun NovelExportDialog(
    dialog: NovelDetailsDialog.ExportEpub,
    onDownloadMissing: () -> Unit,
    onExport: (includePictures: Boolean, from: Int, to: Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var pictures by rememberSaveable { mutableStateOf(true) }
    var wholeBook by rememberSaveable { mutableStateOf(true) }
    var from by rememberSaveable { mutableStateOf("1") }
    var to by rememberSaveable(dialog.onDevice) { mutableStateOf(dialog.onDevice?.toString().orEmpty()) }
    val onDevice = dialog.onDevice
    val range = (from.toIntOrNull() ?: 0) to (to.toIntOrNull() ?: 0)
    val rangeValid = wholeBook || (onDevice != null && range.first in 1..range.second && range.second <= onDevice)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_export_epub)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                when {
                    onDevice == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    onDevice == 0 -> Text(stringResource(MR.strings.export_epub_none))
                    else -> Text(
                        pluralStringResource(MR.plurals.export_epub_available, dialog.total, onDevice, dialog.total),
                    )
                }
                if (dialog.missing.isNotEmpty()) {
                    OutlinedButton(onClick = onDownloadMissing) {
                        Text(stringResource(MR.strings.export_epub_download_missing))
                    }
                }
                if (onDevice != null && onDevice > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(MR.strings.export_epub_pictures), modifier = Modifier.weight(1f))
                        Switch(checked = pictures, onCheckedChange = { pictures = it })
                    }
                    Text(stringResource(MR.strings.chapters), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                        FilterChip(
                            selected = wholeBook,
                            onClick = { wholeBook = true },
                            label = { Text(stringResource(MR.strings.export_epub_range_all)) },
                        )
                        FilterChip(
                            selected = !wholeBook,
                            onClick = { wholeBook = false },
                            label = {
                                Text(
                                    stringResource(
                                        MR.strings.export_epub_range,
                                        stringResource(MR.strings.export_epub_from),
                                        stringResource(MR.strings.export_epub_to),
                                    ),
                                )
                            },
                        )
                    }
                    if (!wholeBook) {
                        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                            NumberField(from, MR.strings.export_epub_from, Modifier.weight(1f)) { from = it }
                            NumberField(to, MR.strings.export_epub_to, Modifier.weight(1f)) { to = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val (first, last) = if (wholeBook) 0 to 0 else range
                    onExport(pictures, first, last)
                },
                enabled = onDevice != null && onDevice > 0 && rangeValid,
            ) {
                Text(stringResource(MR.strings.export))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun NumberField(
    value: String,
    label: dev.icerock.moko.resources.StringResource,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit).take(6)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
