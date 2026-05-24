package yokai.presentation.library.components

import androidx.compose.ui.unit.dp

object CommonMangaItemDefaults {
    // Bumped from 4.dp to match the legacy library grid's per-item 6dp margins (≈ 12dp visible
    // gap between adjacent items). The legacy applies margins inside each manga_grid_item.xml;
    // we centralise the spacing on the LazyGrid so the items can stay padding-free.
    val GridHorizontalSpacer = 8.dp
    val GridVerticalSpacer = 8.dp

    @Suppress("ConstPropertyName")
    const val BrowseFavoriteCoverAlpha = 0.34f
}
