package reikai.presentation.library

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.library.components.LibraryComfortableGrid
import eu.kanade.presentation.library.components.LibraryCompactGrid
import eu.kanade.presentation.library.components.LibraryList
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import tachiyomi.domain.library.model.LibraryDisplayMode

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

    when (displayMode) {
        LibraryDisplayMode.List -> {
            LibraryList(
                items = items,
                contentPadding = contentPadding,
                selection = selection,
                onClick = onClick,
                onLongClick = onLongClick,
                onClickContinueReading = onClickContinueReading,
                searchQuery = searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                drag = drag,
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            LibraryCompactGrid(
                items = items,
                showTitle = displayMode is LibraryDisplayMode.CompactGrid,
                columns = columns,
                contentPadding = contentPadding,
                selection = selection,
                onClick = onClick,
                onLongClick = onLongClick,
                onClickContinueReading = onClickContinueReading,
                searchQuery = searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                drag = drag,
            )
        }
        LibraryDisplayMode.ComfortableGrid, LibraryDisplayMode.ComfortableGridPanorama -> {
            LibraryComfortableGrid(
                items = items,
                usePanoramaCover = displayMode is LibraryDisplayMode.ComfortableGridPanorama,
                columns = columns,
                contentPadding = contentPadding,
                selection = selection,
                onClick = onClick,
                onLongClick = onLongClick,
                onClickContinueReading = onClickContinueReading,
                searchQuery = searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                drag = drag,
            )
        }
    }
}
