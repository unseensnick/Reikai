package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId // RK
import reikai.presentation.library.LibraryCoverEndBadge // RK
import reikai.presentation.library.libraryCoverModel // RK
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
) {
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
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

        items(
            items = items,
            contentType = { "library_list_item" },
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
            MangaListItem(
                isSelected = libraryItem.entryId in selection, // RK
                title = manga.title,
                coverData = libraryCoverModel(libraryItem), // RK: NovelCover for novels, else MangaCover
                badge = {
                    DownloadsBadge(count = libraryItem.badges.downloadCount)
                    UnreadBadge(count = libraryItem.badges.unreadCount)
                    LanguageBadge(
                        isLocal = libraryItem.badges.isLocal,
                        sourceLanguage = libraryItem.badges.sourceLanguage,
                    )
                    LibraryCoverEndBadge(libraryItem) // RK: merge / novel-icon / manga-icon
                },
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
