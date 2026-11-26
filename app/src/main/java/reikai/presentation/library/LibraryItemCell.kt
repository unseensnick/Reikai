package reikai.presentation.library

import androidx.compose.runtime.Composable
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * One library row drawn in [displayMode], for both content types. The pager's grids and list and the
 * single-list view all draw through this, by [libraryItemCell], so the cover model, the two badge groups
 * and the continue-reading rule cannot differ between the two views.
 */
@Composable
fun LibraryItemCell(
    item: LibraryItem,
    displayMode: LibraryDisplayMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    /** Null on a tile a Manual drag owns, whose handle takes the long press instead. */
    onLongClick: (() -> Unit)?,
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

/**
 * The cell [libraryCells] and [libraryRows] draw for [displayMode]: selected when its entry is in
 * [selection], and opening only on a tap the Manual drag has not taken as the end of a long press.
 */
fun libraryItemCell(
    displayMode: LibraryDisplayMode,
    selection: Set<EntryId>,
    onClick: (LibraryItem) -> Unit,
    onClickContinueReading: ((LibraryItem) -> Unit)?,
): @Composable (item: LibraryItem, onLongClick: (() -> Unit)?, tapAllowed: () -> Boolean) -> Unit =
    { item, onLongClick, tapAllowed ->
        LibraryItemCell(
            item = item,
            displayMode = displayMode,
            isSelected = item.entryId in selection,
            onClick = { if (tapAllowed()) onClick(item) },
            onLongClick = onLongClick,
            onClickContinueReading = onClickContinueReading,
        )
    }

/** The cover's continue-reading action: only when there is something unread to resume. */
fun continueReadingFor(item: LibraryItem, onClickContinueReading: ((LibraryItem) -> Unit)?): (() -> Unit)? =
    if (onClickContinueReading != null && item.unreadCount > 0) {
        { onClickContinueReading(item) }
    } else {
        null
    }
