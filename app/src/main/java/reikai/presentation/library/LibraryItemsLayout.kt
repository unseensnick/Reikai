package reikai.presentation.library

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.GlobalSearchItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import reikai.presentation.browse.catalogue.gridCellsFor
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.util.plus

/**
 * Library rows in [displayMode], at the library's column count for the orientation. The library pager and
 * a Statistics row's series list both draw through this, so a mode or a column setting looks the same on
 * both.
 */
@Composable
fun LibraryItemsLayout(
    items: List<LibraryItem>,
    displayMode: LibraryDisplayMode,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    contentPadding: PaddingValues,
    selection: Set<EntryId>,
    onClick: (LibraryItem) -> Unit,
    onLongClick: (LibraryItem) -> Unit,
    onClickContinueReading: ((LibraryItem) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    /** Set when these rows are a Manual category's, whose tiles then drag into a new order. */
    drag: ManualOrderDrag? = null,
) {
    val columns by if (displayMode != LibraryDisplayMode.List) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        remember(isLandscape) { getColumnsForOrientation(isLandscape) }
    } else {
        remember { mutableIntStateOf(0) }
    }

    val cell = libraryItemCell(displayMode, selection, onClick, onClickContinueReading)
    // One branch per family, so switching between the comfortable and compact grids starts a fresh grid
    // state and Manual order, while a switch within a family (to panorama, to cover only) keeps them.
    when (displayMode) {
        LibraryDisplayMode.List -> LibraryItemsList(
            items = items,
            contentPadding = contentPadding,
            searchQuery = searchQuery,
            onGlobalSearchClicked = onGlobalSearchClicked,
            drag = drag,
            onLongClick = onLongClick,
            cell = cell,
        )
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> LibraryItemsGrid(
            items = items,
            columns = columns,
            contentType = "library_compact_grid_item",
            contentPadding = contentPadding,
            searchQuery = searchQuery,
            onGlobalSearchClicked = onGlobalSearchClicked,
            drag = drag,
            onLongClick = onLongClick,
            cell = cell,
        )
        LibraryDisplayMode.ComfortableGrid, LibraryDisplayMode.ComfortableGridPanorama -> LibraryItemsGrid(
            items = items,
            columns = columns,
            contentType = "library_comfortable_grid_item",
            contentPadding = contentPadding,
            searchQuery = searchQuery,
            onGlobalSearchClicked = onGlobalSearchClicked,
            drag = drag,
            onLongClick = onLongClick,
            cell = cell,
        )
    }
}

@Composable
private fun LibraryItemsGrid(
    items: List<LibraryItem>,
    columns: Int,
    contentType: String,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    drag: ManualOrderDrag?,
    onLongClick: (LibraryItem) -> Unit,
    cell: @Composable (item: LibraryItem, onLongClick: (() -> Unit)?, tapAllowed: () -> Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val manualOrder = rememberManualOrder()
    val reorder = rememberManualOrderGrid(gridState, manualOrder) { items }
    FastScrollLazyVerticalGrid(
        columns = gridCellsFor(columns),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = { "library_global_search_item" }) {
                GlobalSearchItem(searchQuery = searchQuery, onClick = onGlobalSearchClicked)
            }
        }
        libraryCells(PAGE_SECTION, items, contentType, drag, manualOrder, reorder, onLongClick, cell)
    }
}

@Composable
private fun LibraryItemsList(
    items: List<LibraryItem>,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    drag: ManualOrderDrag?,
    onLongClick: (LibraryItem) -> Unit,
    cell: @Composable (item: LibraryItem, onLongClick: (() -> Unit)?, tapAllowed: () -> Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val manualOrder = rememberManualOrder()
    val reorder = rememberManualOrderColumn(listState, manualOrder) { items }
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        // Present even without a query, as upstream's list draws it.
        item {
            if (!searchQuery.isNullOrEmpty()) {
                GlobalSearchItem(
                    modifier = Modifier.fillMaxWidth(),
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                )
            }
        }
        libraryRows(PAGE_SECTION, items, "library_list_item", drag, manualOrder, reorder, onLongClick, cell)
    }
}
