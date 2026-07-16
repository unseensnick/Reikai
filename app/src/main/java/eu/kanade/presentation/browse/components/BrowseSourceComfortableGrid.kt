package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.flow.StateFlow
import reikai.presentation.browse.EntryBrowseGridCell
import reikai.presentation.browse.toEntryBrowseUi
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceComfortableGrid(
    // RK: pair carries the gallery metadata for rich rows; this grid renders from .first.
    mangaList: LazyPagingItems<StateFlow<Pair<Manga, RaisedSearchMetadata?>>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // RK: highlighted when in bulk-selection mode
    selection: List<Manga> = emptyList(),
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (mangaList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val pair by mangaList[index]?.collectAsState() ?: return@items
            val manga = pair.first
            BrowseSourceComfortableGridItem(
                manga = manga,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
                isSelected = selection.fastAny { it.id == manga.id },
            )
        }

        if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceComfortableGridItem(
    manga: Manga,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    isSelected: Boolean = false,
) {
    // RK: delegate to the shared manga/novel browse cell so the two catalogues can't drift.
    EntryBrowseGridCell(
        ui = manga.toEntryBrowseUi(),
        displayMode = LibraryDisplayMode.ComfortableGrid,
        onClick = onClick,
        onLongClick = onLongClick,
        isSelected = isSelected,
    )
}
