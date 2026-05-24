package yokai.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import yokai.presentation.core.components.FastScrollLazyVerticalGrid
import yokai.presentation.core.components.FastScrollLazyVerticalStaggeredGrid
import yokai.presentation.core.components.VerticalFastScroller
import yokai.presentation.core.util.plus

@Composable
internal fun LazyLibraryGrid(
    modifier: Modifier = Modifier,
    columns: Int,
    contentPadding: PaddingValues,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit,
) {
    FastScrollLazyVerticalGrid(
        columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(128.dp),
        modifier = modifier,
        state = state,
        // Muted, theme-adaptive gray matching the legacy fast_scroller_handle_idle look.
        // outline alone reads as too vivid in dark mode, so alpha-blend onSurface instead.
        thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        content = content,
    )
}

/**
 * Vertical list variant for `LAYOUT_LIST`. Wraps a [LazyColumn] with the same fast-scroller
 * the grid uses so long libraries are jumpable. Items are one-per-row, full-width.
 */
@Composable
internal fun LazyLibraryList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier) {
        VerticalFastScroller(
            listState = state,
            modifier = Modifier,
            thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            topContentPadding = 0.dp,
            bottomContentPadding = 0.dp,
        ) {
            LazyColumn(
                modifier = Modifier.padding(contentPadding),
                state = state,
                content = content,
            )
        }
    }
}

@Composable
internal fun LazyLibraryStaggeredGrid(
    modifier: Modifier = Modifier,
    columns: Int,
    contentPadding: PaddingValues,
    content: LazyStaggeredGridScope.() -> Unit,
) {
    FastScrollLazyVerticalStaggeredGrid(
        columns = if (columns == 0) StaggeredGridCells.Adaptive(128.dp) else StaggeredGridCells.Fixed(columns),
        modifier = modifier,
        thumbColor = MaterialTheme.colorScheme.outline,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalItemSpacing = CommonMangaItemDefaults.GridVerticalSpacer,
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        content = content,
    )
}
