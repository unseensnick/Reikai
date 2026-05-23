package yokai.presentation.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMPACT_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.presentation.library.components.LazyLibraryGrid
import yokai.presentation.manga.components.MangaComfortableGridItem
import yokai.presentation.manga.components.MangaCompactGridItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    library: Map<Category, List<LibraryItem.Manga>>,
    columns: Int,
    libraryLayout: Int,
    modifier: Modifier = Modifier,
) {
    // Material3 Scaffold with a Compose-side TopAppBar replaces the legacy app bar, which is
    // hidden by BaseComposeController for this controller. No navigation icon: Library is a
    // bottom-nav root, back-arrow would mislead. Compose-side search bar lands in Phase 2.
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(MR.strings.library)) })
        },
    ) { contentPadding ->
        LazyLibraryGrid(
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
                    // Avoid recomputing the cover wrapper and the title getter (which hits the
                    // Injekt-backed CustomMangaManager for favorited manga) on every recompose
                    // triggered by Coil state updates. Each manga.id is unique within the lazy
                    // grid scope so it is a stable cache key.
                    val coverData = remember(manga.id) { manga.cover() }
                    val title = remember(manga.id) { manga.title }
                    when (libraryLayout) {
                        LAYOUT_COMPACT_GRID, LAYOUT_COVER_ONLY_GRID -> {
                            MangaCompactGridItem(coverData = coverData, title = title)
                        }
                        else -> {
                            // LAYOUT_COMFORTABLE_GRID and LAYOUT_LIST (list mode falls back to
                            // comfortable until a list item composable lands in a later phase).
                            MangaComfortableGridItem(coverData = coverData, title = title)
                        }
                    }
                }
            }
        }
    }
}
