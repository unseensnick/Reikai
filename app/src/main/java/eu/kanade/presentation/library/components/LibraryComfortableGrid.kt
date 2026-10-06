package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId // RK
import reikai.presentation.library.LibraryItemCell // RK
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
) {
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        items(
            items = items,
            contentType = { "library_comfortable_grid_item" },
        ) { libraryItem ->
            // RK: one cell for both content types and both library views
            LibraryItemCell(
                item = libraryItem,
                displayMode = if (usePanoramaCover) {
                    LibraryDisplayMode.ComfortableGridPanorama
                } else {
                    LibraryDisplayMode.ComfortableGrid
                },
                isSelected = libraryItem.entryId in selection,
                onClick = { onClick(libraryItem) },
                onLongClick = { onLongClick(libraryItem) },
                onClickContinueReading = onClickContinueReading,
            )
        }
    }
}
