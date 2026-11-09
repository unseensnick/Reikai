package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId // RK
import reikai.presentation.library.LibraryItemCell // RK
import reikai.presentation.library.ManualOrderDrag // RK
import reikai.presentation.library.PAGE_SECTION // RK
import reikai.presentation.library.libraryCells // RK
import reikai.presentation.library.rememberManualOrder // RK
import reikai.presentation.library.rememberManualOrderGrid // RK
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga

@Composable
internal fun LibraryComfortableGrid(
    items: List<LibraryItem>,
    usePanoramaCover: Boolean, // RK: Reikai's panorama mode, which letterboxes a wide cover
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<EntryId>, // RK: neutral identity, a manga and a novel can share a row id
    // RK: the row, not its manga, so the caller knows which content type it belongs to
    onClick: (LibraryItem) -> Unit,
    onLongClick: (LibraryItem) -> Unit,
    onClickContinueReading: ((LibraryItem) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    drag: ManualOrderDrag? = null, // RK: set on a Manual category whose tiles drag into a new order
) {
    // RK --> the Manual drag, one section per page (reikai.presentation.library.ManualOrder)
    val gridState = rememberLazyGridState()
    val manualOrder = rememberManualOrder()
    val reorder = rememberManualOrderGrid(gridState, manualOrder) { items }
    // RK <--
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        state = gridState, // RK
        columns = columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        // RK: one cell for both content types and both library views, draggable in a Manual category
        libraryCells(
            section = PAGE_SECTION,
            rows = items,
            contentType = "library_comfortable_grid_item",
            drag = drag,
            manualOrder = manualOrder,
            reorder = reorder,
            onLongClick = onLongClick,
        ) { libraryItem, longClick, tapAllowed ->
            LibraryItemCell(
                item = libraryItem,
                displayMode = if (usePanoramaCover) {
                    LibraryDisplayMode.ComfortableGridPanorama
                } else {
                    LibraryDisplayMode.ComfortableGrid
                },
                isSelected = libraryItem.entryId in selection,
                onClick = { if (tapAllowed()) onClick(libraryItem) },
                onLongClick = longClick,
                onClickContinueReading = onClickContinueReading,
            )
        }
    }
}
