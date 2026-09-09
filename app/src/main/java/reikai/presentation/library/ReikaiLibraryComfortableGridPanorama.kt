package reikai.presentation.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.library.components.LazyLibraryGrid
import eu.kanade.presentation.library.components.globalSearchItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import tachiyomi.domain.library.model.LibraryManga

/**
 * Pager grid for the panorama display mode. Mirrors Mihon's `LibraryComfortableGrid` (reusing its
 * grid + badges) but renders each cell with [ReikaiComfortableGridPanoramaItem]. Mounted from the
 * Reikai branch of Mihon's `LibraryPager`; the single-list path calls the cell directly.
 */
@Composable
fun ReikaiLibraryComfortableGridPanorama(
    items: List<LibraryItem>,
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<EntryId>,
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
            contentType = { "library_comfortable_grid_panorama_item" },
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
            ReikaiComfortableGridPanoramaItem(
                isSelected = libraryItem.entryId in selection,
                title = manga.title,
                coverData = libraryCoverModel(libraryItem), // NovelCover for novels, else MangaCover
                // RK: both groups share one measured width so neither can overdraw the other
                coverBadgeStart = { LibraryCoverStartBadges(libraryItem) },
                coverBadgeEnd = { LibraryCoverEndBadges(libraryItem) },
                onLongClick = { onLongClick(libraryItem) },
                onClick = { onClick(libraryItem) },
                onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                    { onClickContinueReading(libraryItem) }
                } else {
                    null
                },
            )
        }
    }
}
