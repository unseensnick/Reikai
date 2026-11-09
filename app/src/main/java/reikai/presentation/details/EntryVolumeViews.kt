package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.formatChapterNumber
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.RemoveDone
import reikai.presentation.components.NameDialog
import reikai.presentation.components.rememberSettledReorder
import sh.calvin.reorderable.ReorderableItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The volume chips above a local series' list: every volume, then each one alone. */
internal fun LazyListScope.volumeChipsItem(view: VolumeView, onPick: (String?) -> Unit) {
    item(key = "rk-volume-chips", contentType = "rk-volume-chips") {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            item {
                FilterChip(
                    selected = view.picked == null,
                    onClick = { onPick(null) },
                    label = { Text(stringResource(MR.strings.all_volumes)) },
                )
            }
            items(view.chips, key = { it.key }) { chip ->
                FilterChip(
                    selected = view.picked == chip.key,
                    onClick = { onPick(chip.key) },
                    label = { Text(volumeLabel(chip.label)) },
                )
            }
        }
    }
}

/** A volume's header: its label and read count; a tap selects its chapters where [onSelect] is given, the
 *  button marks them read or unread. */
@Composable
internal fun VolumeHeader(
    volume: EntryChapterListItem.Volume,
    onSelect: (() -> Unit)?,
    onMarkRead: (Boolean) -> Unit,
    coverOf: (suspend () -> Any?)? = null,
) {
    val cover by produceState<Any?>(null, volume.series, volume.key) { value = coverOf?.invoke() }
    val allRead = volume.readCount == volume.chapterIds.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onSelect != null) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(
                    end = 12.dp,
                ).size(width = 36.dp, height = 52.dp).clip(MaterialTheme.shapes.extraSmall),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(volumeLabel(volume.label), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(MR.strings.volume_read_count, volume.readCount, volume.chapterIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onMarkRead(!allRead) }) {
            Icon(
                imageVector = if (allRead) MaterialSymbols.Rounded.RemoveDone else MaterialSymbols.Rounded.DoneAll,
                contentDescription = stringResource(
                    if (allRead) MR.strings.action_mark_as_unread else MR.strings.action_mark_as_read,
                ),
            )
        }
    }
}

@Composable
private fun volumeLabel(label: VolumeLabel): String = when (label) {
    is VolumeLabel.Number -> stringResource(MR.strings.volume_label, formatChapterNumber(label.number))
    is VolumeLabel.Name -> label.name
}

/**
 * Drag a local series' volumes into the order they are read in, and rename one. [onReorder] gets the new
 * order once a drag settles; [onRename] gets null to put back the name the volume's file gives.
 */
@Composable
internal fun ReorderVolumesDialog(
    volumes: List<EntryChapterListItem.Volume>,
    onDismissRequest: () -> Unit,
    onReorder: (keys: List<String>) -> Unit,
    onRename: (key: String, label: String?) -> Unit,
) {
    val items = remember(volumes) { volumes.toMutableStateList() }
    val listState = rememberLazyListState()
    val reorderState = rememberSettledReorder(items, listState, keyOf = { it.key }) { settled ->
        onReorder(settled.map { it.key })
    }
    var renaming by remember { mutableStateOf<EntryChapterListItem.Volume?>(null) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_reorder_volumes)) },
        text = {
            Column {
                // A manga's chapter numbers come from its files, so only the source order follows this one.
                Text(
                    stringResource(MR.strings.volume_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(items, key = { it.key }) { volume ->
                        ReorderableItem(reorderState, key = volume.key) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.DragHandle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp).draggableHandle(),
                                )
                                Text(volumeLabel(volume.label), modifier = Modifier.weight(1f))
                                IconButton(onClick = { renaming = volume }) {
                                    Icon(
                                        MaterialSymbols.Rounded.Edit,
                                        contentDescription = stringResource(MR.strings.action_edit),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_close)) }
        },
    )
    renaming?.let { volume ->
        RenameVolumeDialog(
            current = volumeLabel(volume.label),
            onDismissRequest = { renaming = null },
            onRename = { label ->
                onRename(volume.key, label)
                renaming = null
            },
        )
    }
}

@Composable
private fun RenameVolumeDialog(current: String, onDismissRequest: () -> Unit, onRename: (String?) -> Unit) {
    NameDialog(
        title = stringResource(MR.strings.volume_rename),
        current = current,
        confirmText = stringResource(MR.strings.action_save),
        onDismissRequest = onDismissRequest,
        onConfirm = onRename,
        dismissButton = {
            TextButton(onClick = { onRename(null) }) { Text(stringResource(MR.strings.action_reset)) }
        },
    )
}
