package yokai.presentation.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMPACT_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMFORTABLE_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import yokai.domain.manga.models.cover
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.library.components.LazyLibraryGrid
import yokai.presentation.manga.components.MangaComfortableGridItem
import yokai.presentation.manga.components.MangaCompactGridItem

@Composable
fun LibraryContent(
    library: Map<Category, List<LibraryItem.Manga>>,
    columns: Int,
    libraryLayout: Int,
    modifier: Modifier = Modifier,
) {
    YokaiScaffold(
        onNavigationIconClicked = {},
        appBarType = AppBarType.NONE,
    ) { contentPadding ->
        LazyLibraryGrid(
            modifier = modifier,
            columns = columns,
            contentPadding = contentPadding,
        ) {
            library.forEach { (category, mangaItems) ->
                item(
                    key = "header:${category.id ?: 0}",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "library_category_header",
                ) {
                    Text(
                        text = category.name,
                        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(
                    items = mangaItems,
                    key = { it.libraryManga.manga.id ?: 0L },
                    contentType = { "library_grid_item" },
                ) { item ->
                    val manga = item.libraryManga.manga
                    when (libraryLayout) {
                        LAYOUT_COMPACT_GRID, LAYOUT_COVER_ONLY_GRID -> {
                            MangaCompactGridItem(coverData = manga.cover(), title = manga.title)
                        }
                        else -> {
                            // LAYOUT_COMFORTABLE_GRID and LAYOUT_LIST (list mode falls back to
                            // comfortable until a list item composable lands in a later phase).
                            MangaComfortableGridItem(coverData = manga.cover(), title = manga.title)
                        }
                    }
                }
            }
        }
    }
}
