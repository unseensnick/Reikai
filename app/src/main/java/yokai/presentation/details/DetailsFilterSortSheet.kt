package yokai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.system.getResourceColor

/**
 * Shared chapter filter / sort / display sheet for manga and (later) novels. Holds no domain types:
 * the caller passes resolved flag values (Manga.CHAPTER_* ints) plus callbacks, so both detail
 * surfaces feed it. A Komikku-style tabbed sheet (Filter / Sort / Display) with a ⋮ overflow for
 * Set-as-default / Reset. The Scanlator row hides itself when there are 0-1 scanlators (novels have
 * none). Changes apply immediately; the caller persists and pushes new values back in.
 */
// no ScreenModel: pure UI, state owned by the caller's ScreenModel.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsFilterSortSheet(
    readFilter: Int,
    downloadedFilter: Int,
    bookmarkedFilter: Int,
    hideChapterTitles: Boolean,
    /** Hidden for surfaces without a download system (novels). */
    showDownloadedFilter: Boolean = true,
    onFiltersChanged: (read: Int, downloaded: Int, bookmarked: Int) -> Unit,
    onHideChapterTitlesChanged: (Boolean) -> Unit,
    onSetFilterDefault: () -> Unit,
    onResetFilterDefault: () -> Unit,
    sorting: Int,
    sortDescending: Boolean,
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
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_FILTER) }
    var overflowOpen by remember { mutableStateOf(false) }
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    // Read ?attr/background straight off the theme so the sheet sits flush with the screen behind
    // it (createMdc3Theme doesn't surface the legacy attr as an M3 token); same pattern as the
    // library display sheet.
    val context = LocalContext.current
    val sheetContainerColor = remember(context) { Color(context.getResourceColor(R.attr.background)) }
    // Komikku's AdaptiveSheet: a centered floating dialog on wide screens (tablet / unfolded
    // foldable), a bottom sheet on phones.
    val isTabletUi = LocalConfiguration.current.screenWidthDp >= 600

    val sheetBody: @Composable () -> Unit = {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.weight(1f),
                    containerColor = Color.Transparent,
                    indicator = {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(selectedTab),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    divider = {},
                ) {
                    TabLabel("Filter", selectedTab == TAB_FILTER) { selectedTab = TAB_FILTER }
                    TabLabel("Sort", selectedTab == TAB_SORT) { selectedTab = TAB_SORT }
                    TabLabel("Display", selectedTab == TAB_DISPLAY) { selectedTab = TAB_DISPLAY }
                }
                Box {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Set as default") },
                            onClick = { overflowOpen = false; onSetFilterDefault(); onSetSortDefault() },
                        )
                        DropdownMenuItem(
                            text = { Text("Reset") },
                            onClick = { overflowOpen = false; onResetFilterDefault(); onResetSortDefault() },
                        )
                    }
                }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                when (selectedTab) {
                    TAB_FILTER -> {
                        if (showDownloadedFilter) {
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
                        }
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
                        if (allScanlators.size > 1) {
                            ScanlatorRow(
                                hiddenCount = filteredScanlators.size,
                                onClick = { showScanlatorDialog = true },
                            )
                        }
                    }
                    TAB_SORT -> {
                        SortRow("By source", Manga.CHAPTER_SORTING_SOURCE, sorting, sortDescending, onSortChanged)
                        SortRow("By chapter number", Manga.CHAPTER_SORTING_NUMBER, sorting, sortDescending, onSortChanged)
                        SortRow("By upload date", Manga.CHAPTER_SORTING_UPLOAD_DATE, sorting, sortDescending, onSortChanged)
                    }
                    TAB_DISPLAY -> {
                        RadioRow("Source title", selected = !hideChapterTitles) { onHideChapterTitlesChanged(false) }
                        RadioRow("Chapter number", selected = hideChapterTitles) { onHideChapterTitlesChanged(true) }
                    }
                }
            }
        }
    }

    if (isTabletUi) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.padding(16.dp).widthIn(max = 460.dp),
                shape = RoundedCornerShape(28.dp),
                color = sheetContainerColor,
                shadowElevation = 6.dp,
                tonalElevation = 0.dp,
            ) {
                sheetBody()
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = null,
            containerColor = sheetContainerColor,
        ) {
            sheetBody()
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

private const val TAB_FILTER = 0
private const val TAB_SORT = 1
private const val TAB_DISPLAY = 2

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
private fun TabLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = { Text(label, style = MaterialTheme.typography.labelLarge) },
    )
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
private fun ScanlatorRow(hiddenCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.PeopleAlt,
            contentDescription = null,
            tint = if (hiddenCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Scanlator", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (hiddenCount > 0) {
            Text(
                text = "$hiddenCount hidden",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
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
