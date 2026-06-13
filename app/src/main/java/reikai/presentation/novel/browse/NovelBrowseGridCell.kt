package reikai.presentation.novel.browse

import androidx.compose.runtime.Composable
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import reikai.novel.host.NovelItem
import tachiyomi.domain.manga.model.MangaCover

/**
 * Browse-result cell, a retype of Mihon's `BrowseSourceComfortableGridItem` for [NovelItem] so the LN
 * browse grid is visually identical to the manga catalogue. A browse result is a lightweight
 * `name + cover url + path` with no persisted id, so the cover loads by URL through a synthetic
 * [MangaCover] (ids 0). [inLibrary] dims the cover and shows the in-library badge, matching manga.
 *
 * [MangaComfortableGridItem] sizes to its parent's width and takes no modifier; callers that need a
 * fixed width (the global-search horizontal row) wrap this in a width-constrained Box.
 */
@Composable
fun NovelBrowseGridCell(
    item: NovelItem,
    inLibrary: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    MangaComfortableGridItem(
        title = item.name,
        coverData = MangaCover(
            mangaId = 0L,
            sourceId = 0L,
            isMangaFavorite = inLibrary,
            url = item.cover.orEmpty(),
            lastModified = 0L,
        ),
        coverAlpha = if (inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = { InLibraryBadge(enabled = inLibrary) },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
