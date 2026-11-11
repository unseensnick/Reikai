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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.formatChapterNumber
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.RemoveDone
import reikai.data.local.CombinableSeries
import reikai.data.local.LocalFileNames
import reikai.domain.chapter.LooseFiles
import reikai.presentation.components.NameDialog
import reikai.presentation.components.rememberSettledReorder
import sh.calvin.reorderable.ReorderableItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** The volume chips above a local series' list: every volume, then each one alone. */
internal fun LazyListScope.volumeChipsItem(
    view: VolumeView,
    onPick: (String?) -> Unit,
    onKeepDuplicates: ((renames: List<Pair<String, String>>) -> Unit)? = null,
    onDeleteDuplicates: ((keys: List<String>) -> Unit)? = null,
) {
    if (view.duplicates.isNotEmpty() && onKeepDuplicates != null && onDeleteDuplicates != null) {
        item(key = "rk-volume-duplicates", contentType = "rk-volume-duplicates") {
            DuplicateVolumesNotice(view.duplicates, onKeepDuplicates, onDeleteDuplicates)
        }
    }
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
                    label = { Text(volumeName(chip)) },
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
            Text(volumeName(volume), style = MaterialTheme.typography.titleMedium)
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

/**
 * Volumes that show one label, until the user keeps them all, each later one named as it reads ("Vol. 3 (2)"),
 * or deletes the ones they do not want.
 */
@Composable
private fun DuplicateVolumesNotice(
    duplicates: List<List<EntryChapterListItem.Volume>>,
    onKeep: (renames: List<Pair<String, String>>) -> Unit,
    onDelete: (keys: List<String>) -> Unit,
) {
    val renames = duplicates.flatten().filter { it.duplicate > 0 }.map { it.key to volumeName(it) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        duplicates.forEach { same ->
            Text(
                stringResource(MR.strings.volume_duplicates, same.size, volumeLabel(same.first().label)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row {
            TextButton(onClick = { onKeep(renames) }) { Text(stringResource(MR.strings.volume_duplicates_keep)) }
            TextButton(onClick = { onDelete(duplicates.flatten().map { it.key }) }) {
                Text(stringResource(MR.strings.volume_duplicates_delete))
            }
        }
    }
}

/**
 * Pick volumes to delete from the device, each by its label, file and read count, then confirm, since a
 * deleted file cannot be brought back.
 */
@Composable
internal fun DeleteVolumesDialog(
    volumes: List<EntryChapterListItem.Volume>,
    onDismissRequest: () -> Unit,
    onConfirm: (keys: List<String>) -> Unit,
) {
    val picked = remember { mutableStateListOf<String>() }
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(MR.strings.action_delete_volumes)) },
            text = { Text(pluralStringResource(MR.plurals.delete_volumes_confirm, picked.size, picked.size)) },
            confirmButton = {
                TextButton(onClick = { onConfirm(volumes.map { it.key }.filter { it in picked }) }) {
                    Text(stringResource(MR.strings.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_delete_volumes)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(volumes, key = { it.key }) { volume ->
                    val toggle = { if (!picked.remove(volume.key)) picked += volume.key }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = volume.key in picked, onCheckedChange = { toggle() })
                        VolumeName(volumeName(volume), volume.key, Modifier.weight(1f))
                        Text(
                            "${volume.readCount}/${volume.chapterIds.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirming = true }, enabled = picked.isNotEmpty()) {
                Text(stringResource(MR.strings.action_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

/** A volume's label, with "(2)" and up where an earlier volume shows the same one. */
@Composable
internal fun volumeName(volume: EntryChapterListItem.Volume): String =
    volumeLabel(volume.label).let { if (volume.duplicate > 0) "$it (${volume.duplicate + 1})" else it }

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
    canRenameFiles: Boolean,
    // How a manga's loose files show, or null where there are none to set it for.
    looseFiles: LooseFiles?,
    onLooseFiles: (LooseFiles) -> Unit,
    // renameFiles: a volume was renamed and the files are to follow it.
    onDismissRequest: (renameFiles: Boolean) -> Unit,
    onReorder: (keys: List<String>) -> Unit,
    onRename: (key: String, label: String?) -> Unit,
) {
    val items = remember(volumes) { volumes.toMutableStateList() }
    val listState = rememberLazyListState()
    val reorderState = rememberSettledReorder(items, listState, keyOf = { it.key }) { settled ->
        onReorder(settled.map { it.key })
    }
    var renaming by remember { mutableStateOf<EntryChapterListItem.Volume?>(null) }
    var renamed by remember { mutableStateOf(false) }
    var alsoRenameFiles by remember { mutableStateOf(false) }
    val close = { onDismissRequest(canRenameFiles && renamed && alsoRenameFiles) }
    AlertDialog(
        onDismissRequest = close,
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
                                VolumeName(volumeName(volume), volume.key, Modifier.weight(1f))
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
                looseFiles?.let { LooseFilesChoice(it, onLooseFiles) }
                if (canRenameFiles && renamed) {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.edit_info_also_rename_files),
                        checked = alsoRenameFiles,
                        onCheckedChange = { alsoRenameFiles = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = close) { Text(stringResource(MR.strings.action_close)) }
        },
    )
    renaming?.let { volume ->
        RenameVolumeDialog(
            current = volumeLabel(volume.label),
            onDismissRequest = { renaming = null },
            onRename = { label ->
                onRename(volume.key, label)
                renamed = true
                renaming = null
            },
        )
    }
}

/** The Loose files setting: the chapter files beside a manga's volume folders as one volume, one each, or none. */
@Composable
private fun LooseFilesChoice(current: LooseFiles, onPick: (LooseFiles) -> Unit) {
    var picked by remember(current) { mutableStateOf(current) }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(MR.strings.loose_files), style = MaterialTheme.typography.titleSmall)
        LooseFiles.entries.forEach { mode ->
            val pick = {
                picked = mode
                onPick(mode)
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = pick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == picked, onClick = pick)
                Text(
                    stringResource(
                        when (mode) {
                            LooseFiles.ONE_VOLUME -> MR.strings.loose_files_one_volume
                            LooseFiles.EACH_ITS_OWN -> MR.strings.loose_files_each
                            LooseFiles.NO_VOLUME -> MR.strings.loose_files_none
                        },
                    ),
                )
            }
        }
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

/**
 * Pick the other local series that move into [title] as its volumes. It is offered on the series that
 * receives them, where people look for it; the list loads once it opens.
 */
@Composable
internal fun AddVolumesDialog(
    title: String,
    load: suspend () -> List<CombinableSeries>,
    canOrganize: Boolean,
    onDismissRequest: () -> Unit,
    // organize: the files are to move into this series' folder after, through Organize files' own preview.
    onConfirm: (List<CombinableSeries>, organize: Boolean) -> Unit,
) {
    val series by produceState<List<CombinableSeries>?>(null) { value = load() }
    val picked = remember { mutableStateListOf<CombinableSeries>() }
    var alsoOrganize by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_add_volumes)) },
        text = {
            Column {
                Text(stringResource(MR.strings.add_volumes_summary, title))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(series.orEmpty(), key = { it.id }) { one ->
                        val toggle = { if (!picked.remove(one)) picked += one }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = toggle).testTag("add:${one.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = one in picked, onCheckedChange = { toggle() })
                            VolumeName(one.title, one.folder)
                        }
                    }
                }
                if (canOrganize) {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.edit_info_also_rename_files),
                        checked = alsoOrganize,
                        onCheckedChange = { alsoOrganize = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked.toList(), alsoOrganize) }, enabled = picked.isNotEmpty()) {
                Text(stringResource(MR.strings.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

/**
 * Pick the volumes that become one series of their own, named [title] and the first one's label until the
 * name is edited.
 */
@Composable
internal fun SplitOutDialog(
    title: String,
    volumes: List<EntryChapterListItem.Volume>,
    canOrganize: Boolean,
    onDismissRequest: () -> Unit,
    // organize: the new series' files are to move into a folder of its own, through Organize files' preview.
    onConfirm: (keys: List<String>, name: String, organize: Boolean) -> Unit,
) {
    val picked = remember { mutableStateListOf<String>() }
    var alsoOrganize by remember { mutableStateOf(false) }
    val labels = volumes.associate { it.key to volumeName(it) }
    var edited by remember { mutableStateOf<String?>(null) }
    val name =
        edited ?: volumes.firstOrNull { it.key in picked }?.let { "$title - ${labels.getValue(it.key)}" }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_split_out_volume)) },
        text = {
            Column {
                Text(stringResource(MR.strings.split_out_volume_summary))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(volumes, key = { it.key }) { volume ->
                        val toggle = { if (!picked.remove(volume.key)) picked += volume.key }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = volume.key in picked, onCheckedChange = { toggle() })
                            VolumeName(labels.getValue(volume.key), volume.key)
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { edited = it },
                    label = { Text(stringResource(MR.strings.local_import_series_name)) },
                    singleLine = true,
                    enabled = picked.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (canOrganize) {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.edit_info_also_rename_files),
                        checked = alsoOrganize,
                        onCheckedChange = { alsoOrganize = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(volumes.map { it.key }.filter { it in picked }, name.trim(), alsoOrganize) },
                enabled = picked.isNotEmpty() && name.isNotBlank(),
            ) {
                Text(stringResource(MR.strings.action_split_out))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

/**
 * A volume's [label] and, below it, the file or folder it is, when that names it differently: after a
 * combine, two volumes can share a label while their files still say which series each came from.
 */
@Composable
private fun VolumeName(label: String, key: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label)
        if (key.substringBeforeLast(".epub") != label) {
            Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * What Organize files will name a local series' folder and volumes, old name to new, from the [title] and the
 * [volumes]' labels on screen. The series' folder loads once it opens.
 */
@Composable
internal fun RenameFilesDialog(
    title: String,
    volumes: List<EntryChapterListItem.Volume>,
    plan: suspend (title: String, labels: List<Pair<String, String>>) -> Pair<CombinableSeries, LocalFileNames>?,
    onDismissRequest: () -> Unit,
    onConfirm: (CombinableSeries, LocalFileNames, title: String?) -> Unit,
) {
    // The name the files take, the series' title until the user picks another, which then becomes the title.
    var name by remember { mutableStateOf(title) }
    var choosingName by remember { mutableStateOf(false) }
    val labels = volumes.map { it.key to volumeName(it) }
    val planned by produceState<Pair<CombinableSeries, LocalFileNames>?>(null, name, labels) {
        value = plan(name, labels)
    }
    val series = planned?.first
    val names = planned?.second
    if (choosingName) {
        NameDialog(
            title = stringResource(MR.strings.rename_files_choose_name),
            current = name,
            confirmText = stringResource(MR.strings.action_ok),
            onDismissRequest = { choosingName = false },
            onConfirm = {
                name = it.trim().ifEmpty { name }
                choosingName = false
            },
            dismissButton = {
                TextButton(onClick = { choosingName = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_rename_files)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (names?.changes == false) MR.strings.rename_files_none else MR.strings.rename_files_summary,
                    ),
                )
                names?.taken?.let { taken ->
                    Text(
                        stringResource(MR.strings.rename_files_taken, taken),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { choosingName = true }) {
                        Text(stringResource(MR.strings.rename_files_choose_name))
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(listOfNotNull(names?.series) + names?.volumes.orEmpty()) { rename ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(rename.from, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "\u2192 ${rename.to}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.run {
                                    if (rename.changes) primary else onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { series?.let { s -> names?.let { onConfirm(s, it, name.takeIf { name != title }) } } },
                enabled = names?.changes == true || name != title,
            ) {
                Text(stringResource(MR.strings.action_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}
