package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import reikai.data.local.isLooseBook
import reikai.domain.library.ContentType
import reikai.domain.local.localGroupUnit
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A folder of a local series' group that is gone from the local folder: its [folder] name, and the one [volume] it
 * held where it held exactly one, which then names it. A loose novel book [isFile], not a folder.
 */
@Immutable
data class MissingVolume(val folder: String, val volume: EntryChapterListItem.Volume?, val isFile: Boolean)

/** [missing]'s folders of a [type] series, each named by its one volume on screen where it held one, else by the folder. */
internal fun missingVolumes(
    type: ContentType,
    missing: List<String>,
    chips: List<EntryChapterListItem.Volume>,
): List<MissingVolume> = missing.map { folder ->
    val held = chips.filter { chip -> localGroupUnit(chip.series, chip.key).first == folder }
    MissingVolume(folder, held.singleOrNull(), isLooseBook(type, folder))
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
    LinkFolderDialog(
        message = stringResource(
            MR.strings.local_series_missing_message,
            missing.volume?.let { volumeName(it) } ?: missing.folder,
        ),
        folders = if (choosing) all else matched,
        onDismissRequest = onDismissRequest,
        onLink = onLink,
    ) {
        if (!choosing) {
            TextButton(onClick = { choosing = true }) { Text(stringResource(MR.strings.action_choose_folder)) }
        }
        TextButton(onClick = onRemove) { Text(stringResource(MR.strings.action_remove_from_series)) }
    }
}
