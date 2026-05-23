package yokai.presentation.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
    searchActive: Boolean,
    searchQuery: String,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // System back closes search before exiting the library.
    BackHandler(enabled = searchActive) {
        onSearchQueryChange("")
        onSearchActiveChange(false)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (searchActive) {
                LibrarySearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClose = {
                        onSearchQueryChange("")
                        onSearchActiveChange(false)
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.library)) },
                    actions = {
                        IconButton(onClick = { onSearchActiveChange(true) }) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = stringResource(MR.strings.search),
                            )
                        }
                    },
                )
            }
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
                    // Skip the per-cover loading indicator. With large libraries each Coil state
                    // transition triggers a recompose, which adds up to noticeable cold-start
                    // lag; the cover placeholder color is enough visual cue while loading.
                    when (libraryLayout) {
                        LAYOUT_COMPACT_GRID, LAYOUT_COVER_ONLY_GRID -> {
                            MangaCompactGridItem(
                                coverData = coverData,
                                title = title,
                                showLoadingIndicator = false,
                            )
                        }
                        else -> {
                            // LAYOUT_COMFORTABLE_GRID and LAYOUT_LIST (list mode falls back to
                            // comfortable until a list item composable lands in a later phase).
                            MangaComfortableGridItem(
                                coverData = coverData,
                                title = title,
                                showLoadingIndicator = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // Auto-focus on first composition so the keyboard appears immediately when the user taps
    // the search icon. LaunchedEffect with Unit key fires once per entry into the searchActive
    // branch.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    TopAppBar(
        navigationIcon = {
            // Visual lead, not interactive; tapping the magnifier here would be redundant since
            // the bar is already expanded. Use the X to close.
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.padding(start = 16.dp),
            )
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(MR.strings.library_search_hint)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.close),
                )
            }
        },
    )
}
