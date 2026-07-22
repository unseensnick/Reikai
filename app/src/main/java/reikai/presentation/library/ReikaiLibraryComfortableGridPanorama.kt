package reikai.presentation.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.LazyLibraryGrid
import eu.kanade.presentation.library.components.UnreadBadge
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
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
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
                coverBadgeStart = {
                    DownloadsBadge(count = libraryItem.badges.downloadCount)
                    UnreadBadge(count = libraryItem.badges.unreadCount)
                },
                coverBadgeEnd = {
                    LanguageBadge(
                        isLocal = libraryItem.badges.isLocal,
                        sourceLanguage = libraryItem.badges.sourceLanguage,
                    )
                    LibraryCoverEndBadge(libraryItem) // merge / novel-icon / manga-icon
                },
                onLongClick = { onLongClick(libraryItem.libraryManga) },
                onClick = { onClick(libraryItem.libraryManga) },
                onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                    { onClickContinueReading(libraryItem.libraryManga) }
                } else {
                    null
                },
            )
        }
    }
}
