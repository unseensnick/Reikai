package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import reikai.domain.local.localGroupUnit
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A folder of a local series' group that is gone from the local folder: its [folder] name, and the one [volume] it
 * held where it held exactly one, which then names it. A loose novel book is a file, not a folder.
 */
@Immutable
data class MissingVolume(val folder: String, val volume: EntryChapterListItem.Volume?) {
    val isFile: Boolean get() = folder.endsWith(".epub", ignoreCase = true)
}

/** [missing]'s folders, each named by its one volume on screen where it held one, else by the folder. */
internal fun missingVolumes(missing: List<String>, chips: List<EntryChapterListItem.Volume>): List<MissingVolume> =
    missing.map { folder ->
        val held = chips.filter { chip -> localGroupUnit(chip.series, chip.key).first == folder }
        MissingVolume(folder, held.singleOrNull())
    }

/** A line per missing folder above the list; a tap asks what to do about it. */
internal fun LazyListScope.missingVolumesItem(missing: List<MissingVolume>, onFix: (MissingVolume) -> Unit) {
    if (missing.isEmpty()) return
    item(key = "rk-volume-missing", contentType = "rk-volume-missing") {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            missing.forEach { one ->
                Text(
                    stringResource(
                        if (one.isFile) MR.strings.local_volume_missing_file else MR.strings.local_volume_missing,
                        one.volume?.let { volumeName(it) } ?: one.folder,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable { onFix(one) }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * What to do about a missing volume, never decided for the user: link it to one of the folders holding its chapter
 * files ([candidates]), to any folder the user picks inside the local folder ([choices]), or take it out of the
 * series. Each list loads once it is shown.
 */
@Composable
internal fun MissingVolumeDialog(
    missing: MissingVolume,
    candidates: suspend () -> List<String>,
    choices: suspend () -> List<String>,
    onDismissRequest: () -> Unit,
    onLink: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val matched by produceState<List<String>?>(null) { value = candidates() }
    val all by produceState<List<String>?>(null, choosing) { if (choosing) value = choices() }
    var picked by remember { mutableStateOf<String?>(null) }
    val shown = if (choosing) all else matched
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.local_series_missing_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        MR.strings.local_series_missing_message,
                        missing.volume?.let { volumeName(it) } ?: missing.folder,
                    ),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(shown.orEmpty(), key = { it }) { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { picked = folder },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = folder == picked, onClick = { picked = folder })
                            Text(folder)
                        }
                    }
                }
                if (!choosing) {
                    TextButton(onClick = {
                        choosing = true
                        picked = null
                    }) { Text(stringResource(MR.strings.action_choose_folder)) }
                }
                TextButton(onClick = onRemove) { Text(stringResource(MR.strings.action_remove_from_series)) }
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
