package yokai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.domain.manga.models.Manga

/**
 * Shared chapter filter / sort / scanlator sheet for manga and (later) novels. Holds no domain
 * types: the caller passes resolved flag values (Manga.CHAPTER_* ints) plus callbacks, so both
 * detail surfaces feed it. The scanlator section hides itself when there are 0-1 scanlators (novels
 * have none). Filter and sort changes apply immediately; the caller persists and pushes new values
 * back in, so this sheet stays a pure renderer over its props.
 */
// no ScreenModel: pure UI, state owned by the caller's ScreenModel.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsFilterSortSheet(
    readFilter: Int,
    downloadedFilter: Int,
    bookmarkedFilter: Int,
    hideChapterTitles: Boolean,
    filterMatchesDefault: Boolean,
    onFiltersChanged: (read: Int, downloaded: Int, bookmarked: Int) -> Unit,
    onHideChapterTitlesChanged: (Boolean) -> Unit,
    onSetFilterDefault: () -> Unit,
    onResetFilterDefault: () -> Unit,
    sorting: Int,
    sortDescending: Boolean,
    sortMatchesDefault: Boolean,
    onSortChanged: (sort: Int, descend: Boolean) -> Unit,
    onSetSortDefault: () -> Unit,
    onResetSortDefault: () -> Unit,
    allScanlators: Set<String>,
    filteredScanlators: Set<String>,
    onScanlatorFilterChanged: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showScanlatorDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionHeader("Filter", showButtons = !filterMatchesDefault, onSetDefault = onSetFilterDefault, onReset = onResetFilterDefault)
            TriStateFilterRow(
                label = "Unread",
                state = filterState(readFilter, Manga.CHAPTER_SHOW_UNREAD, Manga.CHAPTER_SHOW_READ),
                onClick = {
                    onFiltersChanged(
                        cycleFilter(readFilter, Manga.CHAPTER_SHOW_UNREAD, Manga.CHAPTER_SHOW_READ),
                        downloadedFilter,
                        bookmarkedFilter,
                    )
                },
            )
            TriStateFilterRow(
                label = "Downloaded",
                state = filterState(downloadedFilter, Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED),
                onClick = {
                    onFiltersChanged(
                        readFilter,
                        cycleFilter(downloadedFilter, Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED),
                        bookmarkedFilter,
                    )
                },
            )
            TriStateFilterRow(
                label = "Bookmarked",
                state = filterState(bookmarkedFilter, Manga.CHAPTER_SHOW_BOOKMARKED, Manga.CHAPTER_SHOW_NOT_BOOKMARKED),
                onClick = {
                    onFiltersChanged(
                        readFilter,
                        downloadedFilter,
                        cycleFilter(bookmarkedFilter, Manga.CHAPTER_SHOW_BOOKMARKED, Manga.CHAPTER_SHOW_NOT_BOOKMARKED),
                    )
                },
            )
            CheckRow(
                label = "Hide chapter titles",
                checked = hideChapterTitles,
                onClick = { onHideChapterTitlesChanged(!hideChapterTitles) },
            )

            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))

            SectionHeader("Sort", showButtons = !sortMatchesDefault, onSetDefault = onSetSortDefault, onReset = onResetSortDefault)
            SortRow("By source", Manga.CHAPTER_SORTING_SOURCE, sorting, sortDescending, onSortChanged)
            SortRow("By chapter number", Manga.CHAPTER_SORTING_NUMBER, sorting, sortDescending, onSortChanged)
            SortRow("By upload date", Manga.CHAPTER_SORTING_UPLOAD_DATE, sorting, sortDescending, onSortChanged)

            if (allScanlators.size > 1) {
                Spacer(Modifier.size(8.dp))
                HorizontalDivider()
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showScanlatorDialog = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Filter groups", style = MaterialTheme.typography.bodyLarge)
                    if (filteredScanlators.isNotEmpty()) {
                        Text(
                            text = "${filteredScanlators.size} hidden",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }

    if (showScanlatorDialog) {
        ScanlatorFilterDialog(
            allScanlators = allScanlators,
            hiddenScanlators = filteredScanlators,
            onConfirm = {
                onScanlatorFilterChanged(it)
                showScanlatorDialog = false
            },
            onReset = {
                onScanlatorFilterChanged(emptySet())
                showScanlatorDialog = false
            },
            onDismiss = { showScanlatorDialog = false },
        )
    }
}

private enum class FilterState { UNCHECKED, CHECKED, IGNORE }

private fun filterState(current: Int, showValue: Int, hideValue: Int): FilterState = when (current) {
    showValue -> FilterState.CHECKED
    hideValue -> FilterState.IGNORE
    else -> FilterState.UNCHECKED
}

private fun cycleFilter(current: Int, showValue: Int, hideValue: Int): Int = when (current) {
    Manga.SHOW_ALL -> showValue
    showValue -> hideValue
    else -> Manga.SHOW_ALL
}

@Composable
private fun SectionHeader(
    title: String,
    showButtons: Boolean,
    onSetDefault: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (showButtons) {
            Row {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onSetDefault) { Text("Set as default") }
            }
        }
    }
}

@Composable
private fun TriStateFilterRow(
    label: String,
    state: FilterState,
    onClick: () -> Unit,
) {
    val (icon, tint) = when (state) {
        FilterState.UNCHECKED -> Icons.Outlined.CheckBoxOutlineBlank to MaterialTheme.colorScheme.onSurfaceVariant
        FilterState.CHECKED -> Icons.Filled.CheckBox to MaterialTheme.colorScheme.primary
        FilterState.IGNORE -> Icons.Filled.IndeterminateCheckBox to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val (icon, tint) = if (checked) {
        Icons.Filled.CheckBox to MaterialTheme.colorScheme.primary
    } else {
        Icons.Outlined.CheckBoxOutlineBlank to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SortRow(
    label: String,
    sortValue: Int,
    currentSort: Int,
    descending: Boolean,
    onSortChanged: (sort: Int, descend: Boolean) -> Unit,
) {
    val selected = sortValue == currentSort
    val arrow: ImageVector? = when {
        !selected -> null
        descending -> Icons.Filled.ArrowDownward
        else -> Icons.Filled.ArrowUpward
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Tapping the selected option flips direction; tapping another selects it ascending.
                if (selected) onSortChanged(sortValue, !descending) else onSortChanged(sortValue, false)
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (arrow != null) {
            Icon(imageVector = arrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.size(24.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ScanlatorFilterDialog(
    allScanlators: Set<String>,
    hiddenScanlators: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scanlators = remember(allScanlators) { allScanlators.toList().sorted() }
    val hidden = remember(hiddenScanlators) {
        scanlators.filter { it in hiddenScanlators }.toMutableStateList()
    }
    // Hiding every scanlator would hide all chapters, so block confirm at that point (legacy parity).
    val canConfirm = hidden.size != scanlators.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter groups") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                scanlators.forEach { scanlator ->
                    val isHidden = scanlator in hidden
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isHidden) hidden.remove(scanlator) else hidden.add(scanlator)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = isHidden, onCheckedChange = null)
                        Text(scanlator, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hidden.toSet()) }, enabled = canConfirm) { Text("Filter") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("Reset") }
        },
    )
}
