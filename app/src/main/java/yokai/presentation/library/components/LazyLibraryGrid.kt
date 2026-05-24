package yokai.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import yokai.presentation.core.components.FastScrollLazyVerticalGrid
import yokai.presentation.core.components.FastScrollLazyVerticalStaggeredGrid
import yokai.presentation.core.util.plus

@Composable
internal fun LazyLibraryGrid(
    modifier: Modifier = Modifier,
    cellMinSizeDp: Int = 128,
    contentPadding: PaddingValues,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit,
) {
    FastScrollLazyVerticalGrid(
        columns = GridCells.Adaptive(cellMinSizeDp.dp),
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
