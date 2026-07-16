package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.flow.StateFlow
import reikai.presentation.browse.EntryBrowseGridCell
import reikai.presentation.browse.toEntryBrowseUi
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    // RK: pair carries the gallery metadata for rich rows; this default list renders from .first.
    mangaList: LazyPagingItems<StateFlow<Pair<Manga, RaisedSearchMetadata?>>>,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // RK: highlighted when in bulk-selection mode
    selection: List<Manga> = emptyList(),
) {
    LazyColumn(
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val pair by mangaList[index]?.collectAsState() ?: return@items
            val manga = pair.first
            BrowseSourceListItem(
                manga = manga,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
                isSelected = selection.fastAny { it.id == manga.id },
            )
        }

        item {
            if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceListItem(
    manga: Manga,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    isSelected: Boolean = false,
) {
    // RK: delegate to the shared manga/novel browse cell so the two catalogues can't drift.
    EntryBrowseGridCell(
        ui = manga.toEntryBrowseUi(),
        displayMode = LibraryDisplayMode.List,
        onClick = onClick,
        onLongClick = onLongClick,
        isSelected = isSelected,
    )
}
