package reikai.presentation.library

import androidx.compose.runtime.Composable
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * One library row drawn in [displayMode], for both content types. Mihon's pager grids and the
 * single-list view all draw through this, so the cover model, the two badge groups and the
 * continue-reading rule cannot differ between the two views.
 */
@Composable
fun LibraryItemCell(
    item: LibraryItem,
    displayMode: LibraryDisplayMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickContinueReading: ((LibraryItem) -> Unit)?,
) {
    val title = item.libraryManga.manga.title
    val coverData = libraryCoverModel(item)
    val onContinueReading = continueReadingFor(item, onClickContinueReading)
    when (displayMode) {
        LibraryDisplayMode.List -> MangaListItem(
            coverData = coverData,
            title = title,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = onContinueReading,
            // Capped at half the row so a merged entry's badges leave the title room
            badgeStart = { LibraryCoverStartBadges(item) },
            badgeEnd = { LibraryCoverEndBadges(item) },
            isSelected = isSelected,
        )
        LibraryDisplayMode.ComfortableGrid, LibraryDisplayMode.ComfortableGridPanorama -> MangaComfortableGridItem(
            coverData = coverData,
            title = title,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = onContinueReading,
            isSelected = isSelected,
            // Both groups share one measured width so neither can overdraw the other
            coverBadgeStart = { LibraryCoverStartBadges(item) },
            coverBadgeEnd = { LibraryCoverEndBadges(item) },
            usePanoramaCover = displayMode is LibraryDisplayMode.ComfortableGridPanorama,
        )
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> MangaCompactGridItem(
            coverData = coverData,
            title = title.takeIf { displayMode is LibraryDisplayMode.CompactGrid },
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = onContinueReading,
            isSelected = isSelected,
            coverBadgeStart = { LibraryCoverStartBadges(item) },
            coverBadgeEnd = { LibraryCoverEndBadges(item) },
        )
    }
}

/** The cover's continue-reading action: only when there is something unread to resume. */
fun continueReadingFor(item: LibraryItem, onClickContinueReading: ((LibraryItem) -> Unit)?): (() -> Unit)? =
    if (onClickContinueReading != null && item.unreadCount > 0) {
        { onClickContinueReading(item) }
    } else {
        null
    }
