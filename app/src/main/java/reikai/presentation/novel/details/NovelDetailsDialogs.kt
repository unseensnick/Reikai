package reikai.presentation.novel.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import reikai.data.novel.NovelStatusCode
import reikai.domain.novel.model.NovelChapterFlags
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource

/** Category picker for adding/moving a novel. */
@Composable
fun NovelCategoryDialog(
    dialog: NovelDetailsDialog.ChangeCategory,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val selected = remember { mutableStateListOf<Long>().apply { addAll(dialog.currentCategoryIds) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(MR.strings.action_move_category)) },
        text = {
            LazyColumn {
                items(items = dialog.allCategories, key = { it.id }) { category ->
                    val checked = category.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (checked) selected.remove(category.id) else selected.add(category.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(text = category.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.toList()) }) { Text(stringResource(MR.strings.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

/** Edit-info form: title / author / artist / description / genres + status. */
@Composable
fun EditNovelInfoDialog(
    dialog: NovelDetailsDialog.EditInfo,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onConfirm: (title: String, author: String, artist: String, description: String, genre: String, status: Long) -> Unit,
) {
    var title by remember { mutableStateOf(dialog.title) }
    var author by remember { mutableStateOf(dialog.author) }
    var artist by remember { mutableStateOf(dialog.artist) }
    var description by remember { mutableStateOf(dialog.description) }
    var genre by remember { mutableStateOf(dialog.genre) }
    var status by remember { mutableStateOf(dialog.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(MR.strings.action_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditField(title, { title = it }, "Title")
                EditField(author, { author = it }, "Author")
                EditField(artist, { artist = it }, "Artist")
                EditField(description, { description = it }, "Description", singleLine = false)
                EditField(genre, { genre = it }, "Genres (comma separated)")
                StatusDropdown(status = status, onSelect = { status = it })
                TextButton(onClick = onReset) { Text(stringResource(MR.strings.action_reset)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, author, artist, description, genre, status) }) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun EditField(value: String, onValueChange: (String) -> Unit, label: String, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatusDropdown(status: Long, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        NovelStatusCode.UNKNOWN to stringResource(MR.strings.unknown_status),
        NovelStatusCode.ONGOING to stringResource(MR.strings.ongoing),
        NovelStatusCode.COMPLETED to stringResource(MR.strings.completed),
        NovelStatusCode.LICENSED to stringResource(MR.strings.licensed),
        NovelStatusCode.PUBLISHING_FINISHED to stringResource(MR.strings.publishing_finished),
        NovelStatusCode.CANCELLED to stringResource(MR.strings.cancelled),
        NovelStatusCode.ON_HIATUS to stringResource(MR.strings.on_hiatus),
    )
    val selectedLabel = options.firstOrNull { it.first.toLong() == status }?.second ?: stringResource(MR.strings.unknown_status)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(text = selectedLabel, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (code, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(code.toLong()); expanded = false })
            }
        }
    }
}

/** Chapter sort / filter / display, mirroring the manga `ChapterSettingsDialog` tabbed layout. */
@Composable
fun NovelChapterSettingsDialog(
    sorting: Long,
    sortDescending: Boolean,
    readFilter: Long,
    bookmarkedFilter: Long,
    hideChapterTitles: Boolean,
    onDismiss: () -> Unit,
    onSortChange: (Long, Boolean) -> Unit,
    onFilterChange: (read: Long, bookmarked: Long) -> Unit,
    onDisplayChange: (Boolean) -> Unit,
    onSetAsDefault: () -> Unit,
    onReset: () -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismiss,
        tabTitles = listOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
        tabOverflowMenuContent = { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.set_chapter_settings_as_default)) },
                onClick = { onSetAsDefault(); closeMenu() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_reset)) },
                onClick = { onReset(); closeMenu() },
            )
        },
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    TriStateItem(
                        label = stringResource(MR.strings.action_filter_unread),
                        state = readFilter.toTriState(NovelChapterFlags.SHOW_UNREAD, NovelChapterFlags.SHOW_READ),
                        onClick = { onFilterChange(it.toReadFlag(), bookmarkedFilter) },
                    )
                    TriStateItem(
                        label = stringResource(MR.strings.action_filter_bookmarked),
                        state = bookmarkedFilter.toTriState(NovelChapterFlags.SHOW_BOOKMARKED, NovelChapterFlags.SHOW_NOT_BOOKMARKED),
                        onClick = { onFilterChange(readFilter, it.toBookmarkFlag()) },
                    )
                }
                1 -> listOf(
                    MR.strings.sort_by_source to NovelChapterFlags.SORTING_SOURCE,
                    MR.strings.sort_by_number to NovelChapterFlags.SORTING_NUMBER,
                    MR.strings.sort_by_upload_date to NovelChapterFlags.SORTING_UPLOAD_DATE,
                ).forEach { (titleRes, mode) ->
                    SortItem(
                        label = stringResource(titleRes),
                        sortDescending = sortDescending.takeIf { sorting == mode },
                        onClick = { onSortChange(mode, toggleDir(sorting == mode, sortDescending)) },
                    )
                }
                2 -> {
                    RadioItem(label = stringResource(MR.strings.show_title), selected = !hideChapterTitles, onClick = { onDisplayChange(false) })
                    RadioItem(label = stringResource(MR.strings.show_chapter_number), selected = hideChapterTitles, onClick = { onDisplayChange(true) })
                }
            }
        }
    }
}

private fun Long.toTriState(isFlag: Long, notFlag: Long): TriState = when (this) {
    isFlag -> TriState.ENABLED_IS
    notFlag -> TriState.ENABLED_NOT
    else -> TriState.DISABLED
}

private fun TriState.toReadFlag(): Long = when (this) {
    TriState.DISABLED -> 0L
    TriState.ENABLED_IS -> NovelChapterFlags.SHOW_UNREAD
    TriState.ENABLED_NOT -> NovelChapterFlags.SHOW_READ
}

private fun TriState.toBookmarkFlag(): Long = when (this) {
    TriState.DISABLED -> 0L
    TriState.ENABLED_IS -> NovelChapterFlags.SHOW_BOOKMARKED
    TriState.ENABLED_NOT -> NovelChapterFlags.SHOW_NOT_BOOKMARKED
}

/** Tapping a selected sort flips direction; tapping an unselected one keeps the current direction. */
private fun toggleDir(isSelected: Boolean, current: Boolean): Boolean = if (isSelected) !current else current
