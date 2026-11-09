package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId // RK
import reikai.presentation.library.LibraryItemCell // RK
import reikai.presentation.library.ManualOrderDrag // RK
import reikai.presentation.library.PAGE_SECTION // RK
import reikai.presentation.library.libraryRows // RK
import reikai.presentation.library.rememberManualOrder // RK
import reikai.presentation.library.rememberManualOrderColumn // RK
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.util.plus

@Composable
internal fun LibraryList(
    items: List<LibraryItem>,
    contentPadding: PaddingValues,
    selection: Set<EntryId>, // RK: neutral identity, a manga and a novel can share a row id
    // RK: the row, not its manga, so the caller knows which content type it belongs to
    onClick: (LibraryItem) -> Unit,
    onLongClick: (LibraryItem) -> Unit,
    onClickContinueReading: ((LibraryItem) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    drag: ManualOrderDrag? = null, // RK: set on a Manual category whose rows drag into a new order
) {
    // RK --> the Manual drag, one section per page (reikai.presentation.library.ManualOrder)
    val listState = rememberLazyListState()
    val manualOrder = rememberManualOrder()
    val reorder = rememberManualOrderColumn(listState, manualOrder) { items }
    // RK <--
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState, // RK
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (!searchQuery.isNullOrEmpty()) {
                GlobalSearchItem(
                    modifier = Modifier.fillMaxWidth(),
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                )
            }
        }

        // RK: one cell for both content types and both library views, draggable in a Manual category
        libraryRows(
            section = PAGE_SECTION,
            rows = items,
            contentType = "library_list_item",
            drag = drag,
            manualOrder = manualOrder,
            reorder = reorder,
            onLongClick = onLongClick,
        ) { libraryItem, longClick, tapAllowed ->
            LibraryItemCell(
                item = libraryItem,
                displayMode = LibraryDisplayMode.List,
                isSelected = libraryItem.entryId in selection,
                onClick = { if (tapAllowed()) onClick(libraryItem) },
                onLongClick = longClick,
                onClickContinueReading = onClickContinueReading,
            )
        }
    }
}
